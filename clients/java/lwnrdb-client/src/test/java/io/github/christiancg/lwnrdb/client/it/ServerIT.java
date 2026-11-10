package io.github.christiancg.lwnrdb.client.it;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ServerIT {
    protected ServerProcess server;
    protected LwnrdbClient client;

    protected Map<String, String> config() {
        return Map.of();
    }

    @BeforeAll
    public void startServer() {
        server = ServerProcess.start(config());
        client = LwnrdbClient.connect(server.settings().build());
    }

    @AfterAll
    public void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    protected static <T> T eventually(java.util.function.Supplier<T> attempt, java.util.function.Predicate<T> done)
            throws InterruptedException {
        final var deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        T last = attempt.get();
        while (!done.test(last) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            last = attempt.get();
        }
        return last;
    }
}
