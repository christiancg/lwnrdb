package org.techhouse.conn;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;
import org.techhouse.metrics.PrometheusText;
import org.techhouse.ops.MetricsExposition;

public class MetricsServer {
    static final int MAX_HEAD_BYTES = 8192;
    static final int MAX_CONCURRENT_SCRAPES = 4;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;
    private static final int BACKLOG = 16;
    private static final int RESPONSE_HEAD_CAPACITY = 256;
    private static final int LINGER_MS = 1000;
    private static final int LINGER_MAX_BYTES = 64 * 1024;
    private static final String METRICS_PATH = "/metrics";
    private static final String BEARER_PREFIX = "Bearer ";
    private final Logger logger = Logger.logFor(MetricsServer.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Semaphore scrapes = new Semaphore(MAX_CONCURRENT_SCRAPES);
    private final Supplier<String> exposition;
    private final int readTimeoutMs;
    private volatile ServerSocket serverSocket;
    private volatile byte[] token = new byte[0];

    public MetricsServer() {
        this(MetricsExposition::render, DEFAULT_READ_TIMEOUT_MS);
    }

    public MetricsServer(Supplier<String> exposition, int readTimeoutMs) {
        this.exposition = exposition;
        this.readTimeoutMs = readTimeoutMs;
    }

    public void start() throws IOException {
        start(configuration.intValue(ConfigKey.METRICS_PORT),
                configuration.stringValue(ConfigKey.METRICS_BIND_ADDRESS).trim(),
                configuration.stringValue(ConfigKey.METRICS_TOKEN));
    }

    public synchronized void start(int port, String bindAddress, String requiredToken) throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("The metrics endpoint is already listening");
        }
        final var address = bindAddress.isEmpty() ? null : InetAddress.getByName(bindAddress);
        final ServerSocket listening;
        try {
            listening = new ServerSocket(port, BACKLOG, address);
        } catch (IOException e) {
            throw new IOException(
                    "could not bind the metrics endpoint to port " + port + " (metricsPort): " + e.getMessage(), e);
        }
        token = requiredToken.getBytes(StandardCharsets.UTF_8);
        serverSocket = listening;
        Thread.ofPlatform().daemon().name("lwnrdb-metrics-accept").start(() -> acceptUntilClosed(listening));
        logger.info("Metrics endpoint is listening on port " + listening.getLocalPort()
                + (token.length == 0 ? "" : " (bearer token required)"));
    }

    public int boundPort() {
        final var listening = serverSocket;
        return listening == null ? -1 : listening.getLocalPort();
    }

    public synchronized void stop() {
        final var listening = serverSocket;
        serverSocket = null;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException e) {
                logger.warning("Failed to close the metrics endpoint: " + e.getMessage());
            }
        }
    }

    private void acceptUntilClosed(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                final var socket = listening.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    logger.warning("Could not accept a metrics connection: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            if (scrapes.tryAcquire()) {
                try {
                    answer(socket);
                } finally {
                    scrapes.release();
                }
            } else {
                respond(socket.getOutputStream(), Reply.of(503, "Too many concurrent scrapes\n"), false);
            }
            lingerUntilTheClientCloses(socket);
        } catch (IOException ignored) {
        }
    }

    private static void lingerUntilTheClientCloses(Socket socket) throws IOException {
        socket.shutdownOutput();
        socket.setSoTimeout(LINGER_MS);
        final var in = socket.getInputStream();
        final var discard = new byte[MAX_HEAD_BYTES];
        var left = LINGER_MAX_BYTES;
        for (var read = in.read(discard); read > 0 && left > 0; read = in.read(discard)) {
            left -= read;
        }
    }

    private void answer(Socket socket) throws IOException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs);
        final var in = new BufferedInputStream(new DeadlineInput(socket, deadline));
        final HttpRequestHead head;
        try {
            head = HttpRequestHead.read(in, MAX_HEAD_BYTES);
        } catch (HttpRequestHead.TooLarge e) {
            respond(socket.getOutputStream(), Reply.of(431, "Request header fields too large\n"), false);
            return;
        } catch (HttpRequestHead.Malformed e) {
            respond(socket.getOutputStream(), Reply.of(400, "Bad request\n"), false);
            return;
        }
        final var headOnly = "HEAD".equals(head.method());
        respond(socket.getOutputStream(), replyTo(head), headOnly);
    }

    private Reply replyTo(HttpRequestHead head) {
        if (!METRICS_PATH.equals(head.path())) {
            return Reply.of(404, "Not found\n");
        }
        if (!"GET".equals(head.method()) && !"HEAD".equals(head.method())) {
            return Reply.of(405, "Method not allowed\n").with("Allow: GET, HEAD");
        }
        if (!authorized(head.header("Authorization"))) {
            return Reply.of(401, "Unauthorized\n").with("WWW-Authenticate: Bearer");
        }
        try {
            return new Reply(200, PrometheusText.CONTENT_TYPE, exposition.get(), null);
        } catch (RuntimeException e) {
            logger.error("Could not render the metrics", e);
            return Reply.of(500, "Could not render the metrics\n");
        }
    }

    private boolean authorized(String authorization) {
        final var required = token;
        if (required.length == 0) {
            return true;
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        final var offered = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.ISO_8859_1);
        return MessageDigest.isEqual(required, offered);
    }

    private static void respond(OutputStream out, Reply reply, boolean headOnly) throws IOException {
        final var body = reply.body().getBytes(StandardCharsets.UTF_8);
        final var head = new StringBuilder(RESPONSE_HEAD_CAPACITY).append("HTTP/1.1 ").append(reply.status())
                .append(' ').append(reasonOf(reply.status())).append("\r\nContent-Type: ").append(reply.contentType())
                .append("\r\nContent-Length: ").append(body.length).append("\r\nConnection: close\r\n");
        if (reply.extraHeader() != null) {
            head.append(reply.extraHeader()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) {
            out.write(body);
        }
        out.flush();
    }

    private static String reasonOf(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 431 -> "Request Header Fields Too Large";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }

    private record Reply(int status, String contentType, String body, String extraHeader) {
        private static final String PLAIN_TEXT = "text/plain; charset=utf-8";

        static Reply of(int status, String body) {
            return new Reply(status, PLAIN_TEXT, body, null);
        }

        Reply with(String header) {
            return new Reply(status, contentType, body, header);
        }
    }

    private static final class DeadlineInput extends FilterInputStream {
        private final Socket socket;
        private final long deadlineNanos;

        DeadlineInput(Socket socket, long deadlineNanos) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            armTimeout();
            return super.read();
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public int read(byte[] buffer, int offset, int length) throws IOException {
            armTimeout();
            return super.read(buffer, offset, length);
        }

        private void armTimeout() throws IOException {
            final var remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                throw new SocketTimeoutException("The metrics request head did not arrive in time");
            }
            socket.setSoTimeout((int) remainingMs);
        }
    }
}
