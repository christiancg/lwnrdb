package org.techhouse.unit.analyze;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;

public class AnalyzeContextTest {

    @AfterEach
    void tearDown() {
        AnalyzeContext.clear();
    }

    @Test
    public void test_current_is_null_by_default() {
        assertNull(AnalyzeContext.current());
    }

    @Test
    public void test_set_and_clear() {
        final var context = new AnalyzeContext();
        AnalyzeContext.set(context);
        assertSame(context, AnalyzeContext.current());
        AnalyzeContext.clear();
        assertNull(AnalyzeContext.current());
    }

    @Test
    public void test_counters_accumulate() {
        final var context = new AnalyzeContext();
        context.addScanned(3);
        context.addScanned(2);
        context.addIndexUsed("status");
        context.addIndexUsed("status");
        context.addIndexUsed("name");
        context.addLock("myDb|myColl");
        context.addLock("myDb|myColl|status");

        assertEquals(5, context.getDocumentsScanned());
        assertEquals(2, context.getIndexesUsed().size());
        assertTrue(context.getIndexesUsed().contains("status"));
        assertTrue(context.getIndexesUsed().contains("name"));
        assertEquals(2, context.getLocksAcquired().size());
        assertTrue(context.getLocksAcquired().contains("myDb|myColl|status"));
    }

    @Test
    public void test_field_lock_id_format() {
        assertEquals("myDb|myColl|status", AnalyzeContext.fieldLockId("myDb", "myColl", "status"));
    }
}
