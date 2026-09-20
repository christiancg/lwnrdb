package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.IndexValueCodec;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PhaseFourLowSeverityTest {
    private final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_integer_min_value_normalizes_the_same_as_the_parser() {
        final var fromIndex = IndexValueCodec.indexValueToElement((double) Integer.MIN_VALUE);
        final var fromDocument = new org.techhouse.ejson.elements.JsonNumber(Integer.toString(Integer.MIN_VALUE));

        assertEquals(fromDocument.hashCode(), fromIndex.hashCode(),
                "the codec and the parser disagreed on exactly Integer.MIN_VALUE, so two equal values hashed"
                        + " differently and an index-backed GROUP_BY emitted two groups with the same key");
        assertEquals(fromDocument, fromIndex);
    }

    @Test
    public void test_an_ordinary_integral_value_still_normalizes_to_an_int() {
        final var fromIndex = IndexValueCodec.indexValueToElement(7.0);
        final var fromDocument = new org.techhouse.ejson.elements.JsonNumber("7");

        assertEquals(fromDocument, fromIndex);
    }

    @Test
    public void test_the_dirty_marker_survives_a_mark_racing_a_clear() {
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "a");
        pendingIndexWrites.mark(TestGlobals.DB, TestGlobals.COLL, "b");

        pendingIndexWrites.clear(TestGlobals.DB, TestGlobals.COLL, "a");

        assertTrue(pendingIndexWrites.idsFor(TestGlobals.DB, TestGlobals.COLL).contains("b"),
                "an id still pending must stay pending");
    }

    @Test
    public void test_a_buffered_op_remembers_the_cascade_depth() {
        final var entry = new AdminTransactionEntry("tx", "client", 0, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        entry.setTriggerContext(List.of("id"), "someone", 3);
        final var data = entry.getData();
        data.addProperty(org.techhouse.config.Globals.PK_FIELD, entry.get_id());

        final var roundTripped = AdminTransactionEntry.fromJsonObject(data);

        assertEquals(3, roundTripped.getTriggerDepth(),
                "a replay that reset the depth to zero ran triggers the online commit suppressed and restarted"
                        + " the cascade budget");
        assertEquals("someone", roundTripped.getActingUser());
    }

    @Test
    public void test_an_op_written_before_the_depth_field_reads_as_zero() {
        final var entry = new AdminTransactionEntry("tx", "client", 0, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        entry.setTriggerContext(List.of(), "someone", 0);
        final var data = entry.getData();
        data.addProperty(org.techhouse.config.Globals.PK_FIELD, entry.get_id());
        data.remove("triggerDepth");

        assertEquals(0, AdminTransactionEntry.fromJsonObject(data).getTriggerDepth());
        assertNotEquals(null, data);
    }
}
