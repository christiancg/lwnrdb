package org.techhouse.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLSocket;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

public final class Connection implements Closeable {
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final Duration READ_TIMEOUT = Duration.ofMinutes(10);
    public static final int MAX_REPLY_BYTES = 256 * 1024 * 1024;
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration BACKOFF_STEP = Duration.ofMillis(200);
    public static final String STATUS = "status";
    public static final String MESSAGE = "message";
    public static final String ERROR_CODE = "errorCode";
    public static final int HELD_REPLY_BYTES = 64 * 1024;
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final Socket socket;
    private final ReplyReader reader;
    private final OutputStream output;
    private final Duration backoffStep;
    private final Duration readTimeout;
    private boolean broken;

    public Connection(Socket socket, Duration backoffStep) throws IOException {
        this(socket, backoffStep, READ_TIMEOUT, MAX_REPLY_BYTES);
    }

    public Connection(Socket socket, Duration backoffStep, Duration readTimeout, int maxReplyBytes) throws IOException {
        this.socket = socket;
        this.reader = new ReplyReader(socket.getInputStream(), maxReplyBytes);
        this.output = socket.getOutputStream();
        this.backoffStep = backoffStep;
        this.readTimeout = readTimeout;
        socket.setSoTimeout(Math.toIntExact(readTimeout.toMillis()));
    }

    public static Connection open(ClientOptions options) {
        final var socket = connect(options);
        try {
            return new Connection(socket, BACKOFF_STEP, READ_TIMEOUT, options.maxReplyBytes());
        } catch (IOException e) {
            closeQuietly(socket);
            throw unreachable(options, e);
        }
    }

    private static Socket connect(ClientOptions options) {
        final var plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(options.host(), options.port()), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(CONNECT_TIMEOUT_MS);
            if (!options.tls()) {
                return plain;
            }
            final var secure = (SSLSocket) TlsSockets.factory(options.insecure()).createSocket(plain, options.host(),
                    options.port(), true);
            if (!options.insecure()) {
                final var parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                secure.setSSLParameters(parameters);
            }
            secure.startHandshake();
            return secure;
        } catch (IOException e) {
            closeQuietly(plain);
            throw unreachable(options, e);
        }
    }

    private static ClientException unreachable(ClientOptions options, IOException e) {
        return new ClientException("cannot connect to " + options.host() + ":" + options.port() + ": " + e,
                ClientException.UNREACHABLE, e);
    }

    public void authenticate(String user, String password) {
        final var reply = send(Requests.authenticate(user, password));
        if (!isOk(reply)) {
            throw new ClientException("authentication refused: " + eJson.toJson(reply), ClientException.UNREACHABLE);
        }
    }

    public JsonObject send(JsonObject request) {
        return sendLine(eJson.toJson(request));
    }

    public JsonObject sendOk(JsonObject request) {
        final var reply = send(request);
        if (!isOk(reply)) {
            throw refused(request, reply);
        }
        return reply;
    }

    public static ClientException refused(JsonObject request, JsonObject reply) {
        return new ClientException(stringOf(request, Requests.TYPE) + " failed: " + eJson.toJson(reply),
                ClientException.FAILED);
    }

    public JsonObject sendLine(String line) {
        return parseReply(sendRawLine(line));
    }

    public String sendRawLine(String line) {
        final var payload = payloadOf(line);
        var attempt = 1;
        var reply = exchange(payload);
        while (attempt < MAX_ATTEMPTS && isRetryable(ReplyHeader.of(reply).errorCode())) {
            pause(attempt);
            attempt++;
            reply = exchange(payload);
        }
        return reply;
    }

    public ReplyHeader relayLine(String line, OutputStream sink) {
        final var payload = payloadOf(line);
        var attempt = 1;
        while (true) {
            final var held = new HeldOutput(sink, HELD_REPLY_BYTES);
            final var header = new ReplyHeader();
            try {
                send(payload);
                if (reader.endsBeforeNextLine(held, header)) {
                    throw closedByServer();
                }
                if (held.spilled() || attempt >= MAX_ATTEMPTS || !isRetryable(header.errorCode())) {
                    held.release();
                    return header;
                }
            } catch (IOException e) {
                throw failed(e);
            }
            pause(attempt);
            attempt++;
        }
    }

    private static byte[] payloadOf(String line) {
        return (line.replace('\r', ' ').replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isRetryable(String errorCode) {
        return errorCode != null && Globals.RETRYABLE_ERROR_CODES.contains(errorCode);
    }

    private String exchange(byte[] payload) {
        final String raw;
        try {
            send(payload);
            raw = reader.readLine();
        } catch (IOException e) {
            throw failed(e);
        }
        if (raw == null) {
            throw closedByServer();
        }
        return raw;
    }

    private void send(byte[] payload) throws IOException {
        output.write(payload);
        output.flush();
    }

    private ClientException failed(IOException e) {
        broken = !(e instanceof ReplyReader.ReplyTooLongException);
        if (e instanceof SocketTimeoutException) {
            return new ClientException("no reply from the server within " + secondsOf(readTimeout) + " s",
                    ClientException.UNREACHABLE, e);
        }
        if (e instanceof ReplyReader.ReplyTooLongException) {
            return new ClientException(e.getMessage() + "; use --exec, which streams a reply, or narrow the request",
                    ClientException.FAILED, e);
        }
        return new ClientException("the connection to the server failed: " + e, ClientException.UNREACHABLE, e);
    }

    private static String secondsOf(Duration duration) {
        final var millis = duration.toMillis();
        return millis % 1000 == 0 ? String.valueOf(millis / 1000) : String.valueOf(millis / 1000.0);
    }

    private static ClientException closedByServer() {
        return new ClientException("the server closed the connection", ClientException.UNREACHABLE);
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(backoffStep.multipliedBy(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("interrupted while retrying", ClientException.FAILED, e);
        }
    }

    public static boolean isReplyTooLong(ClientException failure) {
        return failure.getCause() instanceof ReplyReader.ReplyTooLongException;
    }

    public static JsonObject parseReply(String raw) {
        try {
            return eJson.fromJson(raw, JsonObject.class, Globals.CLIENT_MAX_REPLY_DEPTH);
        } catch (RuntimeException | StackOverflowError e) {
            final var error = new JsonObject();
            error.add(STATUS, new JsonString("ERROR"));
            error.add(MESSAGE, new JsonString(raw));
            return error;
        }
    }

    public static boolean isOk(JsonObject reply) {
        return "OK".equals(stringOf(reply, STATUS));
    }

    public static String errorCodeOf(JsonObject reply) {
        return stringOf(reply, ERROR_CODE);
    }

    public static String stringOf(JsonObject object, String field) {
        final var value = object.get(field);
        return value != null && value.isJsonString() ? value.asJsonString().getValue() : null;
    }

    @Override
    public void close() {
        if (broken) {
            closeQuietly(socket);
            return;
        }
        try (socket) {
            exchange((eJson.toJson(Requests.close()) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (ClientException | IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
