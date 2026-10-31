package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.IndexKind;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexIdentifierMemoTest {
    private static final String FIELD = "memoField";

    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void aMemoizedIdentifierEqualsTheBuiltOneAndIsReused() {
        final var scalar = Cache.memoizedIndexIdentifier(FIELD, String.class);
        final var hashed = Cache.memoizedHashIndexIdentifier(FIELD, IndexKind.OBJECT);

        assertEquals(Cache.getIndexIdentifier(FIELD, String.class), scalar);
        assertEquals(Cache.getHashIndexIdentifier(FIELD, IndexKind.OBJECT.label()), hashed);
        assertSame(scalar, Cache.memoizedIndexIdentifier(FIELD, String.class));
        assertSame(hashed, Cache.memoizedHashIndexIdentifier(FIELD, IndexKind.OBJECT));
    }

    @Test
    public void evictingEveryTypeOfTheFieldForgetsItsIdentifiers() {
        final var before = Cache.memoizedIndexIdentifier(FIELD, Number.class);

        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);

        final var after = Cache.memoizedIndexIdentifier(FIELD, Number.class);
        assertEquals(before, after);
        assertNotSame(before, after);
    }

    @Test
    public void theMemoStaysBoundedWhateverTheNumberOfFields() throws Exception {
        for (var i = 0; i < 10_000; i++) {
            Cache.memoizedIndexIdentifier("field" + i, String.class);
        }

        assertTrue(TestUtils.getPrivateStaticField(Cache.class, "INDEX_IDENTIFIERS", Map.class).size() <= 4096);
        assertEquals(Cache.getIndexIdentifier("field9999", String.class),
                Cache.memoizedIndexIdentifier("field9999", String.class));
    }
}
