package org.techhouse.client;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.SSLSocket;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

public final class Connection implements Closeable {
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 0;
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration BACKOFF_STEP = Duration.ofMillis(200);
    public static final String STATUS = "status";
    public static final String MESSAGE = "message";
    public static final String ERROR_CODE = "errorCode";
    private static final Set<String> RETRYABLE = Set.of("503-2", "503-3", "503-4", "503-5", "503-9", "503-10", "503-16",
            "503-17");
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream output;
    private final Duration backoffStep;

    public Connection(Socket socket, Duration backoffStep) throws IOException {
        this.socket = socket;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.output = socket.getOutputStream();
        this.backoffStep = backoffStep;
    }

    public static Connection open(ClientOptions options) {
        final var socket = connect(options);
        try {
            return new Connection(socket, BACKOFF_STEP);
        } catch (IOException e) {
            closeQuietly(socket);
            throw unreachable(options, e);
        }
    }

    private static Socket connect(ClientOptions options) {
        final var plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(options.host(), options.port()), CONNECT_TIMEOUT_MS);
            plain.setSoTimeout(READ_TIMEOUT_MS);
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
        final var payload = (line.replace('\r', ' ').replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
        var attempt = 1;
        var reply = exchange(payload);
        while (attempt < MAX_ATTEMPTS && isRetryable(reply)) {
            pause(attempt);
            attempt++;
            reply = exchange(payload);
        }
        return reply;
    }

    private static boolean isRetryable(JsonObject reply) {
        final var errorCode = errorCodeOf(reply);
        return errorCode != null && RETRYABLE.contains(errorCode);
    }

    private JsonObject exchange(byte[] payload) {
        final String raw;
        try {
            output.write(payload);
            output.flush();
            raw = reader.readLine();
        } catch (IOException e) {
            throw new ClientException("the connection to the server failed: " + e, ClientException.UNREACHABLE, e);
        }
        if (raw == null) {
            throw new ClientException("the server closed the connection", ClientException.UNREACHABLE);
        }
        return parseReply(raw);
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(backoffStep.multipliedBy(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("interrupted while retrying", ClientException.FAILED, e);
        }
    }

    public static JsonObject parseReply(String raw) {
        try {
            return eJson.fromJson(raw, JsonObject.class);
        } catch (RuntimeException e) {
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
