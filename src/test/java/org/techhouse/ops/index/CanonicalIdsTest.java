package org.techhouse.ops.index;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class CanonicalIdsTest {
    private static final String DB = "canonicalDb";
    private static final String COLL = "canonicalColl";
    private final Cache realCache = IocContainer.get(Cache.class);
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        cache = mock(Cache.class);
        TestUtils.setPrivateStaticField(CanonicalIds.class, "cache", cache);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(CanonicalIds.class, "cache", realCache);
    }

    private static String freshDoc1() {
        return String.valueOf("doc-1".toCharArray());
    }

    @Test
    public void test_an_id_is_resolved_once_for_every_field() {
        final var stored = freshDoc1();
        when(cache.canonicalPkId(DB, COLL, "doc-1")).thenReturn(stored);
        final var ids = new CanonicalIds(DB, COLL);

        for (var field = 0; field < 4; field++) {
            assertSame(stored, ids.of(freshDoc1()));
        }

        verify(cache, times(1)).canonicalPkId(DB, COLL, "doc-1");
    }

    @Test
    public void test_an_absent_id_is_remembered_as_absent() {
        final var ids = new CanonicalIds(DB, COLL);

        assertNull(ids.of("gone"));
        assertNull(ids.of("gone"));

        verify(cache, times(1)).canonicalPkId(DB, COLL, "gone");
    }

    @Test
    public void test_every_memo_starts_empty() {
        when(cache.canonicalPkId(DB, COLL, "doc-2")).thenReturn("doc-2");

        new CanonicalIds(DB, COLL).of("doc-2");
        new CanonicalIds(DB, COLL).of("doc-2");

        verify(cache, times(2)).canonicalPkId(DB, COLL, "doc-2");
    }
}
