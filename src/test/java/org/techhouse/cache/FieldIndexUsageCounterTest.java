package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.test.TestUtils;

public class FieldIndexUsageCounterTest {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static CacheableResource resource(String indexKey) {
        return new CacheableResource(AccessKind.FIELD_INDEX, "db", "coll", indexKey, 1024L);
    }

    @Test
    public void test_field_index_accesses_are_counted_against_the_resource_key() {
        final var tracker = new UsageTracker();
        tracker.recordAccess(AccessKind.FIELD_INDEX, "db", "coll", "score");

        assertEquals(1L, tracker.accessCountFor(resource("score|Double")),
                "the counter is keyed on the field name and the resource on field|Type, so every field index"
                        + " scored zero and LFU degenerated to arbitrary order");
        assertTrue(tracker.lastAccessFor(resource("score|Double")) > 0L);
    }

    @Test
    public void test_a_hot_field_index_outranks_a_cold_one() {
        final var tracker = new UsageTracker();
        for (var access = 0; access < 5; access++) {
            tracker.recordAccess(AccessKind.FIELD_INDEX, "db", "coll", "hot");
        }
        tracker.recordAccess(AccessKind.FIELD_INDEX, "db", "coll", "cold");

        assertTrue(tracker.accessCountFor(resource("hot|Double")) > tracker.accessCountFor(resource("cold|Double")));
    }
}
