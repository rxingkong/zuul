/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.zuul.netty.server;

import com.google.common.annotations.VisibleForTesting;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.netty.common.CategorizedThreadFactory;
import com.netflix.netty.common.LeastConnsEventLoopChooserFactory;
import com.netflix.netty.common.metrics.EventLoopGroupMetrics;
import com.netflix.netty.common.status.ServerStatusManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorChooserFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory;
import io.netty.util.concurrent.ThreadPerTaskExecutor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 * NOTE: Shout-out to <a href="https://github.com/adamfisk/LittleProxy">LittleProxy</a> which was great as a reference.
 *
 * User: michaels
 * Date: 11/8/14
 * Time: 8:39 PM
 */
public class Server
{
    /**
     * This field is effectively a noop, as Epoll is enabled automatically if available.   This can be disabled by
     * using the {@link #FORCE_NIO} property.
     */
    @Deprecated
    public static final DynamicBooleanProperty USE_EPOLL =
            new DynamicBooleanProperty("zuul.server.netty.socket.epoll", false);

    /**
     * If {@code true}, The Zuul server will avoid autodetecting the transport type and use the default Java NIO
     * transport.
     */
    private static final DynamicBooleanProperty FORCE_NIO =
            new DynamicBooleanProperty("zuul.server.netty.socket.force_nio", false);

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);

    private static final DynamicBooleanProperty USE_LEASTCONNS_FOR_EVENTLOOPS =
            new DynamicBooleanProperty("zuul.server.eventloops.use_leastconns", false);

    private final EventLoopGroupMetrics eventLoopGroupMetrics;

    private final Thread jvmShutdownHook = new Thread(this::stop, "Zuul-JVM-shutdown-hook");
    private ServerGroup serverGroup;
    private final ClientConnectionsShutdown clientConnectionsShutdown;
    private final ServerStatusManager serverStatusManager;
    private final Map<? extends SocketAddress, ? extends ChannelInitializer<?>> addressesToInitializers;
    private final EventLoopConfig eventLoopConfig;

    /**
     * This is a hack to expose the channel type to the origin channel.  It is NOT API stable and should not be
     * referenced by non-Zuul code.
     */
    @Deprecated
    public static final AtomicReference<Class<? extends Channel>> defaultOutboundChannelType = new AtomicReference<>();

    /**
     * Use {@link #Server(ServerStatusManager, Map, ClientConnectionsShutdown, EventLoopGroupMetrics, EventLoopConfig)}
     * instead.
     */
    @Deprecated
    public Server(Map<Integer, ChannelInitializer> portsToChannelInitializers, ServerStatusManager serverStatusManager,
                  ClientConnectionsShutdown clientConnectionsShutdown, EventLoopGroupMetrics eventLoopGroupMetrics)
    {
        this(portsToChannelInitializers, serverStatusManager, clientConnectionsShutdown, eventLoopGroupMetrics,
                new DefaultEventLoopConfig());
    }

    /**
     * Use {@link #Server(ServerStatusManager, Map, ClientConnectionsShutdown, EventLoopGroupMetrics, EventLoopConfig)}
     * instead.
     */
    @SuppressWarnings("unchecked") // Channel init map has the wrong generics and we can't fix without api breakage.
    @Deprecated
    public Server(Map<Integer, ChannelInitializer> portsToChannelInitializers, ServerStatusManager serverStatusManager,
                  ClientConnectionsShutdown clientConnectionsShutdown, EventLoopGroupMetrics eventLoopGroupMetrics,
                  EventLoopConfig eventLoopConfig) {
        this(serverStatusManager,
                convertPortMap((Map<Integer, ChannelInitializer<?>>) (Map) portsToChannelInitializers),
                clientConnectionsShutdown, eventLoopGroupMetrics, eventLoopConfig);
    }

    public Server(ServerStatusManager serverStatusManager,
           Map<? extends SocketAddress, ? extends ChannelInitializer<?>> addressesToInitializers,
           ClientConnectionsShutdown clientConnectionsShutdown, EventLoopGroupMetrics eventLoopGroupMetrics,
           EventLoopConfig eventLoopConfig) {
        this.addressesToInitializers = Collections.unmodifiableMap(new LinkedHashMap<>(addressesToInitializers));
        this.serverStatusManager = checkNotNull(serverStatusManager, "serverStatusManager");
        this.clientConnectionsShutdown = checkNotNull(clientConnectionsShutdown, "clientConnectionsShutdown");
        this.eventLoopConfig = checkNotNull(eventLoopConfig, "eventLoopConfig");
        this.eventLoopGroupMetrics = checkNotNull(eventLoopGroupMetrics, "eventLoopGroupMetrics");
    }

    public void stop()
    {
        LOG.info("Shutting down Zuul.");
        serverGroup.stop();

        // remove the shutdown hook that was added when the proxy was started, since it has now been stopped
        try {
            Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
        } catch (IllegalStateException e) {
            // This can happen if the VM is already shutting down
            LOG.debug("Failed to remove shutdown hook", e);
        }
        LOG.info("Completed zuul shutdown.");
    }

    public void start(boolean sync)
    {
        serverGroup = new ServerGroup(
                "Salamander", eventLoopConfig.acceptorCount(), eventLoopConfig.eventLoopCount(), eventLoopGroupMetrics);
        serverGroup.initializeTransport();
        try {
            List<ChannelFuture> allBindFutures = new ArrayList<>(addressesToInitializers.size());

            // Setup each of the channel initializers on requested ports.
            for (Map.Entry<? extends SocketAddress, ? extends ChannelInitializer<?>> entry
                    : addressesToInitializers.entrySet()) {
                ChannelFuture nettyServerFuture = setupServerBootstrap(entry.getKey(), entry.getValue());
                serverGroup.addListeningServer(nettyServerFuture.channel());
                allBindFutures.add(nettyServerFuture);
            }

            // Once all server bootstraps are successfully initialized, then bind to each port.
            for (ChannelFuture f : allBindFutures) {
                // Wait until the server socket is closed.
                ChannelFuture cf = f.channel().closeFuture();
                if (sync) {
                    cf.sync();
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public final List<SocketAddress> getListeningAddresses() {
        if (serverGroup == null) {
            throw new IllegalStateException("Server has not been started");
        }
        return serverGroup.getListeningAddresses();
    }

    @VisibleForTesting
    public void waitForEachEventLoop() throws InterruptedException, ExecutionException
    {
        for (EventExecutor exec : serverGroup.clientToProxyWorkerPool)
        {
            exec.submit(() -> {
                // Do nothing.
            }).get();
        }
    }

    @VisibleForTesting
    public void gracefullyShutdownConnections()
    {
        clientConnectionsShutdown.gracefullyShutdownClientChannels();
    }

    private ChannelFuture setupServerBootstrap(
            SocketAddress listenAddress, ChannelInitializer<?> channelInitializer) throws InterruptedException {
        ServerBootstrap serverBootstrap =
                new ServerBootstrap().group(serverGroup.clientToProxyBossPool, serverGroup.clientToProxyWorkerPool);

        // Choose socket options.
        Map<ChannelOption, Object> channelOptions = new HashMap<>();
        channelOptions.put(ChannelOption.SO_BACKLOG, 128);
        channelOptions.put(ChannelOption.SO_LINGER, -1);
        channelOptions.put(ChannelOption.TCP_NODELAY, true);
        channelOptions.put(ChannelOption.SO_KEEPALIVE, true);

        LOG.info("Proxy listening with " + serverGroup.channelType);
        serverBootstrap.channel(serverGroup.channelType);

        // Apply socket options.
        for (Map.Entry<ChannelOption, Object> optionEntry : channelOptions.entrySet()) {
            serverBootstrap = serverBootstrap.option(optionEntry.getKey(), optionEntry.getValue());
        }
        // Apply transport specific socket options.
        for (Map.Entry<ChannelOption, ?> optionEntry : serverGroup.transportChannelOptions.entrySet()) {
            serverBootstrap = serverBootstrap.option(optionEntry.getKey(), optionEntry.getValue());
        }

        serverBootstrap.childHandler(channelInitializer);
        serverBootstrap.validate();

        LOG.info("Binding to : " + listenAddress);

        // Flag status as UP just before binding to the port.
        serverStatusManager.localStatus(InstanceInfo.InstanceStatus.UP);

        // Bind and start to accept incoming connections.
        return serverBootstrap.bind(listenAddress).sync();
    }

    /**
     * Override for metrics or informational purposes
     *
     * @param clientToProxyBossPool - acceptor pool
     * @param clientToProxyWorkerPool - worker pool
     */
    public void postEventLoopCreationHook(EventLoopGroup clientToProxyBossPool, EventLoopGroup clientToProxyWorkerPool) {

    }

    private final class ServerGroup
    {
        /** A name for this ServerGroup to use in naming threads. */
        private final String name;
        private final int acceptorThreads;
        private final int workerThreads;
        private final EventLoopGroupMetrics eventLoopGroupMetrics;
        private final Thread jvmShutdownHook = new Thread(this::stop, "Zuul-ServerGroup-JVM-shutdown-hook");

        private EventLoopGroup clientToProxyBossPool;
        private EventLoopGroup clientToProxyWorkerPool;
        private Class<? extends ServerChannel> channelType;
        private Map<ChannelOption, ?> transportChannelOptions;

        private volatile boolean stopped = false;

        private final Set<Channel> nettyServers = new LinkedHashSet<>();

        void addListeningServer(Channel channel) {
            nettyServers.add(channel);
        }

        private ServerGroup(String name, int acceptorThreads, int workerThreads, EventLoopGroupMetrics eventLoopGroupMetrics) {
            this.name = name;
            this.acceptorThreads = acceptorThreads;
            this.workerThreads = workerThreads;
            this.eventLoopGroupMetrics = eventLoopGroupMetrics;

            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(final Thread t, final Throwable e) {
                    LOG.error("Uncaught throwable", e);
                }
            });

            Runtime.getRuntime().addShutdownHook(jvmShutdownHook);
        }

        synchronized List<SocketAddress> getListeningAddresses() {
            if (stopped) {
                return Collections.emptyList();
            }
            List<SocketAddress> listeningAddresses = new ArrayList<>(nettyServers.size());
            for (Channel nettyServer : nettyServers) {
                listeningAddresses.add(nettyServer.localAddress());
            }
            return Collections.unmodifiableList(listeningAddresses);
        }

        private void initializeTransport()
        {
            // TODO - try our own impl of ChooserFactory that load-balances across the eventloops using leastconns algo?
            EventExecutorChooserFactory chooserFactory;
            if (USE_LEASTCONNS_FOR_EVENTLOOPS.get()) {
                chooserFactory = new LeastConnsEventLoopChooserFactory(eventLoopGroupMetrics);
            } else {
                chooserFactory = DefaultEventExecutorChooserFactory.INSTANCE;
            }

            ThreadFactory workerThreadFactory = new CategorizedThreadFactory(name + "-ClientToZuulWorker");
            Executor workerExecutor = new ThreadPerTaskExecutor(workerThreadFactory);

            Map<ChannelOption, Object> extraOptions = new HashMap<>();
            boolean useNio = FORCE_NIO.get();
            if (!useNio && epollIsAvailable()) {
                channelType = EpollServerSocketChannel.class;
                defaultOutboundChannelType.set(EpollSocketChannel.class);
                extraOptions.put(EpollChannelOption.TCP_DEFER_ACCEPT, -1);
                clientToProxyBossPool = new EpollEventLoopGroup(
                        acceptorThreads,
                        new CategorizedThreadFactory(name + "-ClientToZuulAcceptor"));
                clientToProxyWorkerPool = new EpollEventLoopGroup(
                        workerThreads,
                        workerExecutor,
                        chooserFactory,
                        DefaultSelectStrategyFactory.INSTANCE);
            } else if (!useNio && kqueueIsAvailable()) {
                channelType = KQueueServerSocketChannel.class;
                defaultOutboundChannelType.set(KQueueSocketChannel.class);
                clientToProxyBossPool = new KQueueEventLoopGroup(
                        acceptorThreads,
                        new CategorizedThreadFactory(name + "-ClientToZuulAcceptor"));
                clientToProxyWorkerPool = new KQueueEventLoopGroup(
                        workerThreads,
                        workerExecutor,
                        chooserFactory,
                        DefaultSelectStrategyFactory.INSTANCE);
            } else {
                channelType = NioServerSocketChannel.class;
                defaultOutboundChannelType.set(NioSocketChannel.class);
                NioEventLoopGroup elg = new NioEventLoopGroup(
                        workerThreads,
                        workerExecutor,
                        chooserFactory,
                        SelectorProvider.provider(),
                        DefaultSelectStrategyFactory.INSTANCE
                );
                elg.setIoRatio(90);
                clientToProxyBossPool = new NioEventLoopGroup(
                        acceptorThreads,
                        new CategorizedThreadFactory(name + "-ClientToZuulAcceptor"));
                clientToProxyWorkerPool = elg;
            }

            transportChannelOptions = Collections.unmodifiableMap(extraOptions);

            postEventLoopCreationHook(clientToProxyBossPool, clientToProxyWorkerPool);
        }

        synchronized private void stop()
        {
            LOG.info("Shutting down");
            if (stopped) {
                LOG.info("Already stopped");
                return;
            }

            // TODO(carl-mastrangelo): shutdown the netty servers accepting new connections.
            nettyServers.clear();

            // Flag status as down.
            // TODO - is this _only_ changing the local status? And therefore should we also implement a HealthCheckHandler
            // that we can flag to return DOWN here (would that then update Discovery? or still be a delay?)
            serverStatusManager.localStatus(InstanceInfo.InstanceStatus.DOWN);

            // Shutdown each of the client connections (blocks until complete).
            // NOTE: ClientConnectionsShutdown can also be configured to gracefully close connections when the
            // discovery status changes to DOWN. So if it has been configured that way, then this will be an additional
            // call to gracefullyShutdownClientChannels(), which will be a noop.
            clientConnectionsShutdown.gracefullyShutdownClientChannels();

            LOG.info("Shutting down event loops");
            List<EventLoopGroup> allEventLoopGroups = new ArrayList<>();
            allEventLoopGroups.add(clientToProxyBossPool);
            allEventLoopGroups.add(clientToProxyWorkerPool);
            for (EventLoopGroup group : allEventLoopGroups) {
                group.shutdownGracefully();
            }

            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(20, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    LOG.warn("Interrupted while shutting down event loop");
                }
            }
            try {
                Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
            } catch (IllegalStateException e) {
                // This can happen if the VM is already shutting down
                LOG.debug("Failed to remove shutdown hook", e);
            }

            stopped = true;
            LOG.info("Done shutting down");
        }
    }

    static Map<SocketAddress, ChannelInitializer<?>> convertPortMap(
            Map<Integer, ChannelInitializer<?>> portsToChannelInitializers) {
        Map<SocketAddress, ChannelInitializer<?>> addrsToInitializers =
                new LinkedHashMap<>(portsToChannelInitializers.size());
        for (Map.Entry<Integer, ChannelInitializer<?>> portToInitializer : portsToChannelInitializers.entrySet()){
            addrsToInitializers.put(new InetSocketAddress(portToInitializer.getKey()), portToInitializer.getValue());
        }
        return Collections.unmodifiableMap(addrsToInitializers);
    }

    private static boolean epollIsAvailable() {
        boolean available;
        try {
            available = Epoll.isAvailable();
        } catch (NoClassDefFoundError e) {
            LOG.debug("Epoll is unavailable, skipping", e);
            return false;
        } catch (RuntimeException | Error e) {
            LOG.warn("Epoll is unavailable, skipping", e);
            return false;
        }
        if (!available) {
            LOG.debug("Epoll is unavailable, skipping", Epoll.unavailabilityCause());
        }
        return available;
    }

    private static boolean kqueueIsAvailable() {
        boolean available;
        try {
            available = KQueue.isAvailable();
        } catch (NoClassDefFoundError e) {
            LOG.debug("KQueue is unavailable, skipping", e);
            return false;
        } catch (RuntimeException | Error e) {
            LOG.warn("KQueue is unavailable, skipping", e);
            return false;
        }
        if (!available) {
            LOG.debug("KQueue is unavailable, skipping", KQueue.unavailabilityCause());
        }
        return available;
    }
}