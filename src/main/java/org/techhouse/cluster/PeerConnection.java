package org.techhouse.cluster;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class PeerConnection {
    private final Logger logger = Logger.logFor(PeerConnection.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ClusterRpc rpc = new ClusterRpc();
    private final Socket socket;
    private final BufferedWriter writer;
    private final ReentrantLock writerLock = new ReentrantLock();
    private final Thread readerThread;
    private volatile boolean closed;

    public PeerConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.readerThread = Thread.ofVirtual().name("cluster-peer-reader").start(() -> readLoop(reader));
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
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
        writerLock.lock();
        try {
            writer.write(eJson.toJson(message));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            rpc.fail(correlationId, e);
            throw e;
        } finally {
            writerLock.unlock();
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
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
