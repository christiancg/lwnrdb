package org.techhouse.cluster;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.conn.BoundedLineReader;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class PeerConnection {
    private final Logger logger = Logger.logFor(PeerConnection.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final ClusterRpc rpc = new ClusterRpc();
    private final Socket socket;
    private final BufferedWriter writer;
    private final ReentrantLock writerLock = new ReentrantLock();
    private final Thread readerThread;
    private volatile boolean closed;

    public PeerConnection(Socket socket) throws IOException {
        this.socket = socket;
        try {
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            socket.setSoTimeout((int) Math.min(clusterConfig.replicationAckTimeoutMs(), Integer.MAX_VALUE));
            write(PeerRequest.message(ClusterMessageType.HELLO));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        final var reader = new BoundedLineReader(socket.getInputStream(), socket::setSoTimeout);
        this.readerThread = Thread.ofVirtual().name("cluster-peer-reader").start(() -> readLoop(reader));
    }

    private void write(ClusterMessage message) throws IOException {
        writerLock.lock();
        try {
            writer.write(eJson.toJson(message));
            writer.newLine();
            writer.flush();
        } finally {
            writerLock.unlock();
        }
    }

    private void readLoop(BoundedLineReader reader) {
        try {
            final var maxFrameBytes = clusterConfig.maxFrameBytes();
            String line;
            while (!closed && (line = reader.readLine(maxFrameBytes)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final var message = eJson.fromJson(line, ClusterMessage.class);
                if (message != null && message.getCorrelationId() != null) {
                    rpc.complete(message.getCorrelationId(), message);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                logger.warning("Cluster peer connection closed: " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    public ClusterMessage sendRequest(ClusterMessage message, long timeoutMs)
            throws IOException, InterruptedException, TimeoutException {
        final var correlationId = rpc.newCorrelationId();
        message.setCorrelationId(correlationId);
        final var future = rpc.register(correlationId);
        try {
            write(message);
        } catch (IOException e) {
            rpc.fail(correlationId, e);
            throw e;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            // Drop the correlation either way, or a caller that gave up leaves an entry no reply will ever
            // remove. A response that arrives afterwards finds nothing pending and is discarded.
            rpc.fail(correlationId, e);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("Cluster request failed", e.getCause());
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        rpc.failAll(new IOException("Cluster peer connection closed"));
        try {
            socket.close();
        } catch (IOException e) {
            logger.warning("Error closing cluster peer connection: " + e.getMessage());
        }
        readerThread.interrupt();
    }
}
