package org.techhouse.unit.client.support;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.techhouse.client.Connection;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class FakeLineServer implements Closeable {
    public static final String HANG_UP = "<hang up>";
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final ServerSocket server;
    private final Map<String, Function<JsonObject, String>> responders = new ConcurrentHashMap<>();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    public FakeLineServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public Connection connect() throws IOException {
        return new Connection(new Socket(InetAddress.getLoopbackAddress(), port()), Duration.ZERO);
    }

    public FakeLineServer reply(String type, String... replies) {
        final Deque<String> queue = new ArrayDeque<>(List.of(replies));
        responders.put(type, ignored -> queue.size() > 1 ? queue.poll() : queue.peek());
        return this;
    }

    public void respond(String type, Function<JsonObject, String> responder) {
        responders.put(type, responder);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public List<JsonObject> requests() {
        final var parsed = new ArrayList<JsonObject>();
        for (final var line : lines) {
            parsed.add(eJson.fromJson(line, JsonObject.class));
        }
        return parsed;
    }

    public List<String> types() {
        final var types = new ArrayList<String>();
        for (final var request : requests()) {
            types.add(Connection.stringOf(request, "type"));
        }
        return types;
    }

    public static String ok(String type) {
        return ok(type, "");
    }

    public static String ok(String type, String extraFields) {
        final var extra = extraFields.isEmpty() ? "" : "," + extraFields;
        return "{\"type\":\"" + type + "\",\"status\":\"OK\",\"message\":\"Ok\",\"errorCode\":null" + extra + "}";
    }

    public static String error(String type, String status, String errorCode) {
        return "{\"type\":\"" + type + "\",\"status\":\"" + status + "\",\"message\":\"refused\",\"errorCode\":\""
                + errorCode + "\"}";
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final var socket = server.accept();
                accepted.add(socket);
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            final var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            final OutputStream output = socket.getOutputStream();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                final var reply = replyTo(line);
                if (HANG_UP.equals(reply)) {
                    return;
                }
                output.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private String replyTo(String line) {
        final JsonObject request;
        try {
            request = eJson.fromJson(line, JsonObject.class);
        } catch (RuntimeException e) {
            return "The command is not valid";
        }
        final var type = Connection.stringOf(request, "type");
        final var responder = responders.get(type);
        return responder == null ? ok(type) : responder.apply(request);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (final var socket : accepted) {
            socket.close();
        }
    }
}
