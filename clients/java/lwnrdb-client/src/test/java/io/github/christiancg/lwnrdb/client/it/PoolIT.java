package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.util.ArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class PoolIT extends ServerIT {
    @Test
    public void test_many_threads_share_a_small_pool_and_every_write_lands() throws Exception {
        client.database("pool_it").create();
        client.database("pool_it").collection("events").create();
        try (var pooled = LwnrdbClient.connect(ItSettings.of(server).poolSize(4).build())) {
            final var events = pooled.database("pool_it").collection("events");
            final var tasks = new ArrayList<FutureTask<Integer>>();
            for (var thread = 0; thread < 32; thread++) {
                final var prefix = "t" + thread + "-";
                final var task = new FutureTask<>(() -> {
                    for (var i = 0; i < 200; i++) {
                        events.save(Document.of("_id", prefix + i, "n", i));
                    }
                    return 200;
                });
                new Thread(task, "pool-it-" + thread).start();
                tasks.add(task);
            }
            var written = 0;
            for (final var task : tasks) {
                written += task.get(5, TimeUnit.MINUTES);
            }
            assertEquals(6400, written);
            assertEquals(6400, events.query().count());
        }
    }

    @Test
    public void test_closing_the_client_while_requests_run_fails_them_cleanly() throws Exception {
        client.database("pool_close_it").create();
        client.database("pool_close_it").collection("events").create();
        final var closing = LwnrdbClient.connect(ItSettings.of(server).poolSize(2).build());
        final var events = closing.database("pool_close_it").collection("events");
        final var tasks = new ArrayList<FutureTask<Integer>>();
        for (var thread = 0; thread < 4; thread++) {
            final var prefix = "c" + thread + "-";
            final var task = new FutureTask<>(() -> {
                var done = 0;
                try {
                    for (var i = 0; i < 10_000; i++) {
                        events.save(Document.of("_id", prefix + i));
                        done++;
                    }
                } catch (LwnrdbException expected) {
                }
                return done;
            });
            new Thread(task, "pool-close-it-" + thread).start();
            tasks.add(task);
        }
        Thread.sleep(300);
        closing.close();
        for (final var task : tasks) {
            task.get(1, TimeUnit.MINUTES);
        }
    }
}
