package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.tx.ApplyOutcome;

public class ApplyOutcomeTest {

    @Test
    public void test_an_equal_stored_version_is_already_applied() {
        assertEquals(ApplyOutcome.ALREADY_APPLIED, ApplyOutcome.forSave(7L, 0L, 7L));
    }

    @Test
    public void test_a_higher_stored_version_supersedes_a_save() {
        assertEquals(ApplyOutcome.SUPERSEDED, ApplyOutcome.forSave(8L, 0L, 7L));
    }

    @Test
    public void test_a_tombstone_at_or_above_the_op_supersedes_a_save() {
        assertEquals(ApplyOutcome.SUPERSEDED, ApplyOutcome.forSave(0L, 7L, 7L));
        assertEquals(ApplyOutcome.SUPERSEDED, ApplyOutcome.forSave(0L, 9L, 7L));
    }

    @Test
    public void test_an_older_stored_version_or_tombstone_lets_a_save_apply() {
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forSave(6L, 0L, 7L));
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forSave(0L, 6L, 7L));
    }

    @Test
    public void test_an_absent_id_is_applied_for_a_save() {
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forSave(0L, 0L, 7L));
    }

    @Test
    public void test_a_delete_of_an_absent_id_is_already_applied() {
        assertEquals(ApplyOutcome.ALREADY_APPLIED, ApplyOutcome.forDelete(0L, false, 7L));
    }

    @Test
    public void test_a_higher_stored_version_supersedes_a_delete() {
        assertEquals(ApplyOutcome.SUPERSEDED, ApplyOutcome.forDelete(8L, true, 7L));
    }

    @Test
    public void test_a_delete_of_an_older_or_equal_document_applies() {
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forDelete(6L, true, 7L));
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forDelete(7L, true, 7L));
    }

    @Test
    public void test_a_version_of_zero_applies_unconditionally() {
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forSave(99L, 99L, 0L));
        assertEquals(ApplyOutcome.APPLIED, ApplyOutcome.forDelete(99L, true, 0L));
    }

    @Test
    public void test_only_a_superseded_op_withholds_its_triggers() {
        assertTrue(ApplyOutcome.APPLIED.firesTriggers());
        assertTrue(ApplyOutcome.ALREADY_APPLIED.firesTriggers());
        assertFalse(ApplyOutcome.SUPERSEDED.firesTriggers());
    }
}
