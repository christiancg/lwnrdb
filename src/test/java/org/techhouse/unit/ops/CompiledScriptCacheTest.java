package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ops.CompiledScriptCache;
import org.techhouse.test.TestUtils;

public class CompiledScriptCacheTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptCompiledCacheSize", 128);
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetSize() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptCompiledCacheSize", 128);
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

    // The failure is cached too: a client looping on a broken script would otherwise re-parse every call
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
        TestUtils.setPrivateField(configuration, "scriptCompiledCacheSize", 2);
        final var cache = new CompiledScriptCache();
        final var oldest = cache.get("return 1;");
        cache.get("return 2;");
        cache.get("return 3;");
        assertEquals(2, cache.size());
        assertNotSame(oldest, cache.get("return 1;"));
    }

    @Test
    public void test_touching_an_entry_keeps_it() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptCompiledCacheSize", 2);
        final var cache = new CompiledScriptCache();
        final var first = cache.get("return 1;");
        cache.get("return 2;");
        cache.get("return 1;");
        cache.get("return 3;");
        assertSame(first, cache.get("return 1;"));
    }

    @Test
    public void test_zero_size_disables_caching() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptCompiledCacheSize", 0);
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
