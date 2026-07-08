package org.techhouse.cluster;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.SocketFactory;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.config.Configuration;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.ioc.IocContainer;

public class PeerConnectionPool {
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final Map<NodeAddress, PeerConnection> connections = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile SocketFactory socketFactory;

    public ClusterMessage request(NodeAddress address, ClusterMessage message, long timeoutMs)
            throws IOException, InterruptedException, TimeoutException {
        final var connection = getOrCreate(address);
        try {
            return connection.sendRequest(message, timeoutMs);
        } catch (IOException | TimeoutException | InterruptedException e) {
            drop(address, connection);
            throw e;
        }
    }

    private PeerConnection getOrCreate(NodeAddress address) throws IOException {
        lock.lock();
        try {
            var connection = connections.get(address);
            if (connection != null && !connection.isClosed()) {
                return connection;
            }
            final var socket = socketFactory().createSocket(address.getHost(), address.getPort());
            connection = new PeerConnection(socket);
            connections.put(address, connection);
            return connection;
        } finally {
            lock.unlock();
        }
    }

    private void drop(NodeAddress address, PeerConnection connection) {
        connection.close();
        connections.remove(address, connection);
    }

    private SocketFactory socketFactory() {
        var factory = socketFactory;
        if (factory == null) {
            lock.lock();
            try {
                factory = socketFactory;
                if (factory == null) {
                    factory = clusterConfig.tlsEnabled()
                            ? TlsContextFactory.createSocketFactory(Configuration.getInstance())
                            : SocketFactory.getDefault();
                    socketFactory = factory;
                }
            } finally {
                lock.unlock();
            }
        }
        return factory;
    }

    public void closeAll() {
        for (var entry : Map.copyOf(connections).entrySet()) {
            drop(entry.getKey(), entry.getValue());
        }
    }
}
