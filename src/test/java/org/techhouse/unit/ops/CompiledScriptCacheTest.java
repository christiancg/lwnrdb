package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledScriptCache;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.test.TestUtils;

public class CompiledScriptCacheTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "procedureCacheMaxBytes",
                ConfigKey.PROCEDURE_CACHE_MAX_BYTES.defaultValue());
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetSize() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "procedureCacheMaxBytes",
                ConfigKey.PROCEDURE_CACHE_MAX_BYTES.defaultValue());
    }

    private static String sourceOfLength(int index, int length) {
        final var prefix = "return " + index + ";";
        return prefix + " ".repeat(length - prefix.length());
    }

    @Test
    public void test_the_cache_is_bounded_by_bytes_not_count() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheMaxBytes", String.valueOf(25 * 100 * 3));
        final var cache = new CompiledScriptCache();
        final var oldest = cache.get(sourceOfLength(0, 100));
        for (var i = 1; i < 6; i++) {
            cache.get(sourceOfLength(i, 100));
        }
        assertEquals(3, cache.size());
        assertTrue(cache.weightedBytes() <= 25 * 100 * 3);
        assertNotSame(oldest, cache.get(sourceOfLength(0, 100)));
    }

    @Test
    public void test_a_program_larger_than_the_byte_budget_is_compiled_but_not_kept() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheMaxBytes", String.valueOf(25 * 50));
        final var cache = new CompiledScriptCache();
        final var large = cache.get(sourceOfLength(1, 100));
        assertNotNull(large.compiled());
        assertEquals(0, cache.size());
        assertEquals(0, cache.weightedBytes());
        cache.get(sourceOfLength(2, 40));
        assertEquals(1, cache.size());
    }

    @Test
    public void test_compilation_happens_outside_the_lock() throws Exception {
        final var cache = new CompiledScriptCache();
        final var real = IocContainer.get(SimpleJs.class);
        final var stalled = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var blocking = mock(SimpleJs.class);
        when(blocking.compile(anyString(), anyBoolean())).thenAnswer(invocation -> {
            final String source = invocation.getArgument(0);
            if (source.startsWith("return 'slow'")) {
                stalled.countDown();
                release.await();
            }
            return real.compile(source, false);
        });
        TestUtils.setPrivateField(cache, "simpleJs", blocking);
        final var slow = Thread.ofVirtual().start(() -> cache.get("return 'slow';"));
        try {
            assertTrue(stalled.await(10, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertNotNull(cache.get("return 'fast';").compiled()));
        } finally {
            release.countDown();
            slow.join();
        }
        assertEquals(2, cache.size());
    }

    @Test
    public void test_identical_source_is_compiled_once() {
        final var cache = new CompiledScriptCache();
        final var first = cache.get("return 1;");
        final var second = cache.get("return 1;");
        assertSame(first, second);
        assertNull(first.failure());
        assertNotNull(first.compiled());
        assertEquals(1, cache.size());
    }

    @Test
    public void test_different_source_gets_its_own_entry() {
        final var cache = new CompiledScriptCache();
        assertNotSame(cache.get("return 1;"), cache.get("return 2;"));
        assertEquals(2, cache.size());
    }

    @Test
    public void test_syntax_error_is_cached_and_replayed() {
        final var cache = new CompiledScriptCache();
        final var first = cache.get("return (;");
        final var second = cache.get("return (;");
        assertNotNull(first.failure());
        assertNull(first.compiled());
        assertSame(first.failure(), second.failure());
        assertEquals(1, cache.size());
    }

    @Test
    public void test_evicts_least_recently_used() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 2);
        final var cache = new CompiledScriptCache();
        final var oldest = cache.get("return 1;");
        cache.get("return 2;");
        cache.get("return 3;");
        assertEquals(2, cache.size());
        assertNotSame(oldest, cache.get("return 1;"));
    }

    @Test
    public void test_touching_an_entry_keeps_it() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 2);
        final var cache = new CompiledScriptCache();
        final var first = cache.get("return 1;");
        cache.get("return 2;");
        cache.get("return 1;");
        cache.get("return 3;");
        assertSame(first, cache.get("return 1;"));
    }

    @Test
    public void test_zero_size_disables_caching() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 0);
        final var cache = new CompiledScriptCache();
        assertNotSame(cache.get("return 1;"), cache.get("return 1;"));
        assertEquals(0, cache.size());
    }

    // A parse tree has no database affinity, so byte-identical sources deliberately share one entry
    @Test
    public void test_identical_sources_share_one_entry_regardless_of_caller() {
        final var cache = new CompiledScriptCache();
        cache.get("return 1;");
        cache.get("return 1;");
        assertEquals(1, cache.size());
    }
}
