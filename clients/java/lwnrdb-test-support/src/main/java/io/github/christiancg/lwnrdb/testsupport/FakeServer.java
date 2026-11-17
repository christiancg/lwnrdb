package io.github.christiancg.lwnrdb.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class FakeServer implements AutoCloseable {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ServerSocket server;
    private final Responder responder;
    private final BlockingQueue<ObjectNode> requests = new LinkedBlockingQueue<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final AtomicInteger accepted = new AtomicInteger();

    @FunctionalInterface
    public interface Responder {
        void respond(ObjectNode request, Session session) throws IOException;
    }

    public FakeServer(Responder responder) throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.responder = responder;
        final var acceptor = new Thread(this::acceptLoop, "fake-server-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public static Responder okToEverything() {
        return (request, session) -> session.send(ok(request));
    }

    public static ObjectNode ok(ObjectNode request) {
        return MAPPER.createObjectNode().put("type", request.path("type").asString()).put("status", "OK").put("message",
                "Ok");
    }

    public static ObjectNode error(ObjectNode request, String status, String code) {
        return MAPPER.createObjectNode().put("type", request.path("type").asString()).put("status", status)
                .put("message", "refused").put("errorCode", code);
    }

    public int port() {
        return server.getLocalPort();
    }

    public int acceptedConnections() {
        return accepted.get();
    }

    public List<Session> sessions() {
        return sessions;
    }

    @SuppressWarnings("BusyWait")
    public Session awaitSession(int index) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sessions.size() <= index) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("session " + index + " never connected");
            }
            Thread.sleep(5);
        }
        return sessions.get(index);
    }

    public ObjectNode nextRequest() throws InterruptedException {
        final var request = requests.poll(10, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("no request arrived within 10 s");
        }
        return request;
    }

    public ObjectNode nextRequestOfType(String type) throws InterruptedException {
        while (true) {
            final var request = nextRequest();
            if (type.equals(request.path("type").asString())) {
                return request;
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (final var session : sessions) {
            session.close();
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final var socket = server.accept();
                accepted.incrementAndGet();
                final var session = new Session(socket);
                sessions.add(session);
                final var thread = new Thread(() -> serve(session), "fake-server-session");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Session session) {
        try (var reader = new BufferedReader(
                new InputStreamReader(session.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final var request = (ObjectNode) MAPPER.readTree(line);
                requests.add(request);
                responder.respond(request, session);
            }
        } catch (IOException e) {
            session.close();
        }
    }

    public static final class Session {
        private final Socket socket;
        private final OutputStream out;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        public synchronized void send(ObjectNode frame) throws IOException {
            sendRaw(MAPPER.writeValueAsString(frame) + "\n");
        }

        public synchronized void sendRaw(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
