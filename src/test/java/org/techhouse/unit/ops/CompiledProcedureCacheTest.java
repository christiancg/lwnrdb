package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.test.TestUtils;

public class CompiledProcedureCacheTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetSize() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
    }

    @Test
    public void test_second_get_with_same_version_returns_identical_instance() {
        final var cache = new CompiledProcedureCache();
        final var first = cache.get("db", "p", 1L, "return 1;");
        final var second = cache.get("db", "p", 1L, "return 1;");
        assertSame(first, second);
        assertEquals(1, cache.size());
    }

    // A save bumps the version, so a stale entry can never be served - no invalidation hook needed
    @Test
    public void test_version_bump_misses_the_cache() {
        final var cache = new CompiledProcedureCache();
        final var first = cache.get("db", "p", 1L, "return 1;");
        final var second = cache.get("db", "p", 2L, "return 2;");
        assertNotSame(first, second);
        assertEquals("return 2;", second.source());
        assertEquals(2, cache.size());
    }

    @Test
    public void test_evicts_least_recently_used_past_configured_size() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 2);
        final var cache = new CompiledProcedureCache();
        final var first = cache.get("db", "a", 1L, "return 1;");
        cache.get("db", "b", 1L, "return 2;");
        // Touch "a" so "b" becomes the least recently used.
        assertSame(first, cache.get("db", "a", 1L, "return 1;"));
        cache.get("db", "c", 1L, "return 3;");
        assertEquals(2, cache.size());
        assertSame(first, cache.get("db", "a", 1L, "return 1;"));
        assertNotSame(first, cache.get("db", "b", 1L, "return 2;"));
    }

    @Test
    public void test_size_zero_compiles_every_time() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 0);
        final var cache = new CompiledProcedureCache();
        assertNotSame(cache.get("db", "p", 1L, "return 1;"), cache.get("db", "p", 1L, "return 1;"));
        assertEquals(0, cache.size());
    }

    @Test
    public void test_invalidate_database_drops_only_that_database() {
        final var cache = new CompiledProcedureCache();
        final var kept = cache.get("otherdb", "p", 1L, "return 1;");
        final var dropped = cache.get("db", "p", 1L, "return 1;");
        cache.invalidateDatabase("db");
        assertEquals(1, cache.size());
        assertSame(kept, cache.get("otherdb", "p", 1L, "return 1;"));
        assertNotSame(dropped, cache.get("db", "p", 1L, "return 1;"));
    }

    @Test
    public void test_invalidate_procedure_drops_only_that_procedure() {
        final var cache = new CompiledProcedureCache();
        final var kept = cache.get("db", "keep", 1L, "return 1;");
        final var dropped = cache.get("db", "drop", 1L, "return 1;");
        cache.invalidateProcedure("db", "drop");
        assertSame(kept, cache.get("db", "keep", 1L, "return 1;"));
        assertNotSame(dropped, cache.get("db", "drop", 1L, "return 1;"));
    }
}
