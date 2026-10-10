package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class DefinitionCachePublishRaceTest {
    private static final String DB = "db";
    private static final String NAME = "proc";
    private static final int PUBLISH_LOOKUP = 2;

    private final BoundedLruCache<String> values = new BoundedLruCache<>(Integer.MAX_VALUE, Long.MAX_VALUE, _ -> 1L);
    private final BoundedLruCache<Boolean> misses = new BoundedLruCache<>(Integer.MAX_VALUE, Long.MAX_VALUE, _ -> 1L);
    private final CountDownLatch publishing = new CountDownLatch(1);
    private final CountDownLatch mayPublish = new CountDownLatch(1);
    private final AtomicInteger loaderThreadLookups = new AtomicInteger();
    private volatile Thread loaderThread;

    private BoundedLruCache<String> valuesPausingAtPublish() {
        if (Thread.currentThread() == loaderThread && loaderThreadLookups.incrementAndGet() == PUBLISH_LOOKUP) {
            publishing.countDown();
            try {
                if (!mayPublish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the paused publish");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return values;
    }

    @Test
    public void test_a_remove_between_the_generation_check_and_the_publish_is_not_undone() throws Exception {
        final var cache = new DefinitionCache<>(this::valuesPausingAtPublish, () -> misses, "miss:",
                (_, _) -> "loaded");
        final var id = Cache.getCollectionIdentifier(DB, NAME);
        final var loaded = new String[1];
        final var loader = new Thread(() -> loaded[0] = cache.get(DB, NAME));
        loaderThread = loader;
        loader.start();
        assertTrue(publishing.await(5, TimeUnit.SECONDS), "the load must reach its publish");

        final var remover = new Thread(() -> cache.remove(id));
        remover.start();
        remover.join(200);
        mayPublish.countDown();
        loader.join(5000);
        remover.join(5000);

        assertEquals("loaded", loaded[0]);
        assertNull(values.get(id),
                "a delete that lands after the generation check must still win over the load's publish");
    }
}
