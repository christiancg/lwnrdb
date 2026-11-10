package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.error.RequestTimeoutException;
import io.github.christiancg.lwnrdb.client.error.ServerClosedConnectionException;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.protocol.Status;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

public final class WireConnection implements AutoCloseable {
    private static final AtomicInteger READER_IDS = new AtomicInteger();
    private static final byte NEWLINE = '\n';

    private final Socket socket;
    private final OutputStream out;
    private final Json json;
    private final LineReader reader;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Deque<PendingReply> pending = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread readerThread;
    private volatile Consumer<ObjectNode> pushSink = frame -> {
    };
    private volatile Consumer<LwnrdbException> closeListener = cause -> {
    };
    private volatile LwnrdbException closeCause;

    public WireConnection(Socket socket, Json json, long maxReplyBytes) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.json = json;
        this.reader = new LineReader(socket.getInputStream(), maxReplyBytes);
        this.readerThread = new Thread(this::readLoop, "lwnrdb-reader-" + READER_IDS.incrementAndGet());
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public void onPush(Consumer<ObjectNode> sink) {
        this.pushSink = sink;
    }

    public void onClose(Consumer<LwnrdbException> listener) {
        this.closeListener = listener;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public CompletableFuture<ObjectNode> send(ObjectNode request) {
        final var payload = json.encodeFrame(request);
        final var reply = new PendingReply(new CompletableFuture<>(),
                Operation.CLOSE_CONNECTION.name().equals(request.path("type").asString("")));
        writeLock.lock();
        try {
            if (closed.get()) {
                reply.future().completeExceptionally(lostCause());
                return reply.future();
            }
            pending.addLast(reply);
            out.write(payload);
            out.write(NEWLINE);
            out.flush();
        } catch (IOException e) {
            close(new ConnectionLostException("could not send the request to " + socket.getRemoteSocketAddress(), e));
        } finally {
            writeLock.unlock();
        }
        return reply.future();
    }

    public ObjectNode request(ObjectNode request, Duration timeout) {
        final var future = send(request);
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            final var timedOut = new RequestTimeoutException("no reply within " + timeout + "; the connection was"
                    + " closed, so the request's outcome is unknown");
            close(timedOut);
            throw timedOut;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof LwnrdbException lwnrdb
                    ? lwnrdb
                    : new ConnectionLostException("the request failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close(new ConnectionLostException("interrupted while waiting for a reply", e));
            throw new ConnectionLostException("interrupted while waiting for a reply", e);
        }
    }

    public void closeGracefully(Duration timeout) {
        if (closed.get()) {
            return;
        }
        try {
            request(json.values().createObjectNode().put("type", Operation.CLOSE_CONNECTION.name()), timeout);
        } catch (LwnrdbException ignored) {
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        close(new ConnectionLostException("connection closed by the client", null));
    }

    public void close(LwnrdbException cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeCause = cause;
        try {
            socket.close();
        } catch (IOException ignored) {
            closeCause.addSuppressed(ignored);
        }
        PendingReply reply;
        while ((reply = pending.pollFirst()) != null) {
            reply.future().completeExceptionally(cause);
        }
        closeListener.accept(cause);
    }

    private LwnrdbException lostCause() {
        final var cause = closeCause;
        return cause != null ? cause : new ConnectionLostException("the connection is closed", null);
    }

    private void readLoop() {
        try {
            LineReader.Line line;
            while ((line = reader.next()) != null) {
                if (line.tooLarge()) {
                    failHead(new LwnrdbException("the reply exceeded maxReplyBytes and was discarded", null));
                } else if (line.length() > 0) {
                    dispatchLine(line);
                }
            }
            close(new ConnectionLostException("the server closed the connection", null));
        } catch (IOException e) {
            close(new ConnectionLostException("lost the connection to " + socket.getRemoteSocketAddress(), e));
        } catch (LwnrdbException e) {
            close(e);
        } catch (RuntimeException e) {
            close(new ConnectionLostException("the reply reader failed: " + e.getMessage(), e));
        }
    }

    private void dispatchLine(LineReader.Line line) {
        final ObjectNode frame;
        try {
            frame = json.parseFrame(line.bytes(), 0, line.length());
        } catch (JacksonException | MappingException notJson) {
            failHead(new LwnrdbException("the server refused the request: "
                    + new String(line.bytes(), 0, line.length(), StandardCharsets.UTF_8), null));
            return;
        }
        dispatch(frame);
    }

    private void dispatch(ObjectNode frame) {
        final var head = pending.peekFirst();
        switch (FrameRouter.route(frame, head != null && head.awaitsClose())) {
            case PUSH -> pushSink.accept(frame);
            case SERVER_CLOSE -> close(serverClosed(frame));
            default -> completeHead(frame);
        }
    }

    private void completeHead(ObjectNode frame) {
        final var head = pending.pollFirst();
        if (head == null) {
            close(new ConnectionLostException("the server sent a reply nobody asked for: " + json.text(frame), null));
            return;
        }
        head.future().complete(frame);
    }

    private void failHead(LwnrdbException cause) {
        final var head = pending.pollFirst();
        if (head != null) {
            head.future().completeExceptionally(cause);
        }
    }

    private static ServerClosedConnectionException serverClosed(ObjectNode frame) {
        return new ServerClosedConnectionException(
                "the server closed the connection: " + frame.path("message").asString(""),
                frame.path("errorCode").asString(null), Status.ERROR);
    }
}
