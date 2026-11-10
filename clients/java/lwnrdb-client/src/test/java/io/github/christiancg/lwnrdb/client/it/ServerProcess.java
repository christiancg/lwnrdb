package io.github.christiancg.lwnrdb.client.it;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class ServerProcess implements AutoCloseable {
    public static final String ADMIN = "admin";
    public static final String PASSWORD = "Tst-Admin-9xQ2mV";
    private static final Duration STARTUP = Duration.ofSeconds(90);

    private final Path workDir;
    private final int port;
    private final Process process;

    private ServerProcess(Map<String, String> overrides) throws IOException, InterruptedException {
        this.workDir = Files.createTempDirectory("lwnrdb-it");
        this.port = freePort();
        final var config = new LinkedHashMap<String, String>();
        config.put("port", String.valueOf(port));
        config.put("filePath", "db");
        config.put("logPath", "logs");
        config.put("defaultAdminPassword", PASSWORD);
        config.put("maxMemory", "128Mb");
        config.putAll(overrides);
        final var text = new StringBuilder();
        config.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(workDir.resolve("lwnrdb.cfg"), text.toString(), StandardCharsets.UTF_8);
        final var jar = Path.of(System.getProperty("lwnrdb.jar", "../../../target/lwnrdb.jar")).toAbsolutePath();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("no server jar at " + jar + "; run mvn package -DskipTests at the root");
        }
        this.process = new ProcessBuilder("java", "-Xmx512m", "-XX:+UseG1GC", "-jar", jar.toString())
                .directory(workDir.toFile()).redirectErrorStream(true)
                .redirectOutput(workDir.resolve("server.out").toFile()).start();
        awaitPort();
    }

    public static ServerProcess start(Map<String, String> overrides) {
        try {
            return new ServerProcess(overrides);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return port;
    }

    public Path workDir() {
        return workDir;
    }

    public ClientSettings.Builder settings() {
        return ClientSettings.builder().host("localhost", port).credentials(ADMIN, PASSWORD)
                .requestTimeout(Duration.ofSeconds(60));
    }

    @Override
    public void close() throws IOException, InterruptedException {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
        }
        try (Stream<Path> paths = Files.walk(workDir)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void awaitPort() throws IOException, InterruptedException {
        final var deadline = System.nanoTime() + STARTUP.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "the server exited: " + Files.readString(workDir.resolve("server.out")));
            }
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 200);
                return;
            } catch (IOException e) {
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException("the server did not open port " + port + " within " + STARTUP);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
