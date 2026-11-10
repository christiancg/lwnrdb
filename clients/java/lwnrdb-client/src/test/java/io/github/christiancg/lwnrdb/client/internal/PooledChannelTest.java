package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.UnavailableException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.testing.FakeServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class PooledChannelTest {
    private final List<Duration> pauses = new ArrayList<>();

    private PooledChannel channel(ConnectionPool pool, RetryPolicy policy) {
        return new PooledChannel(pool, policy, Duration.ofSeconds(5), pauses::add);
    }

    private static FakeServer failingFirst(int failures, String code) throws Exception {
        final var calls = new AtomicInteger();
        return new FakeServer((request, session) -> {
            if (calls.incrementAndGet() <= failures) {
                session.send(FakeServer.error(request, "ERROR", code));
            } else {
                session.send(FakeServer.ok(request));
            }
        });
    }

    @Test
    public void test_a_retryable_refusal_is_retried_with_growing_backoff() throws Exception {
        try (var server = failingFirst(2, "503-2");
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            final var reply = channel(pool, RetryPolicy.defaults()).send(Operation.AGGREGATE,
                    Wire.request("AGGREGATE"));
            assertEquals("OK", reply.get("status").asString());
            assertEquals(List.of(Duration.ofMillis(50), Duration.ofMillis(100)), pauses);
        }
    }

    @Test
    public void test_retries_stop_at_max_attempts() throws Exception {
        try (var server = failingFirst(10, "503-17");
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            assertThrows(UnavailableException.class,
                    () -> channel(pool, new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)))
                            .send(Operation.AGGREGATE, Wire.request("AGGREGATE")));
            assertEquals(2, pauses.size());
        }
    }

    @Test
    public void test_an_unknown_outcome_and_a_non_retryable_503_are_never_retried() throws Exception {
        try (var server = failingFirst(1, "500-34");
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            assertThrows(UnknownOutcomeException.class,
                    () -> channel(pool, RetryPolicy.defaults()).send(Operation.SAVE, Wire.request("SAVE")));
        }
        try (var server = failingFirst(1, "503-3");
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            assertThrows(UnavailableException.class,
                    () -> channel(pool, RetryPolicy.defaults()).send(Operation.SAVE, Wire.request("SAVE")));
        }
        assertTrue(pauses.isEmpty());
    }

    @Test
    public void test_a_lost_connection_is_retried_for_reads_only() throws Exception {
        final var calls = new AtomicInteger();
        try (var server = new FakeServer((request, session) -> {
            if (calls.incrementAndGet() == 1) {
                session.close();
            } else {
                session.send(FakeServer.ok(request));
            }
        }); var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            assertEquals("OK", channel(pool, RetryPolicy.defaults())
                    .send(Operation.AGGREGATE, Wire.request("AGGREGATE")).get("status").asString());
            calls.set(0);
            assertThrows(ConnectionLostException.class,
                    () -> channel(pool, RetryPolicy.defaults()).send(Operation.SAVE, Wire.request("SAVE")));
        }
    }

    @Test
    public void test_no_retry_policy_sends_once() throws Exception {
        try (var server = failingFirst(1, "503-2");
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            assertThrows(UnavailableException.class,
                    () -> channel(pool, RetryPolicy.none()).send(Operation.AGGREGATE, Wire.request("AGGREGATE")));
            assertTrue(pauses.isEmpty());
        }
    }

    @Test
    public void test_sleeping_backoff_pauses_and_honours_interrupts() {
        final var backoff = Backoff.sleeping();
        final var start = System.nanoTime();
        backoff.pause(Duration.ofMillis(20));
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(9).toNanos());
        Thread.currentThread().interrupt();
        assertThrows(ConnectionLostException.class, () -> backoff.pause(Duration.ofMillis(50)));
        assertTrue(Thread.interrupted());
    }
}
