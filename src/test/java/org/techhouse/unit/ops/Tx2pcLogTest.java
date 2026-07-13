package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.test.TestUtils;

public class Tx2pcLogTest {
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
    public void test_participant_marker_round_trip() throws Exception {
        final var dtxId = "11111111-1111-1111-1111-111111111111";
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:9000", List.of("127.0.0.1:9000", "127.0.0.1:9001"),
                List.of("db|collA", "db|collB"));
        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertTrue(Tx2pcLog.preparedDtxIds().contains(dtxId));
        final var marker = Tx2pcLog.readParticipantMarker(dtxId);
        assert marker != null;
        assertEquals("127.0.0.1:9000", marker.coordinatorAddress());
        assertEquals(List.of("127.0.0.1:9000", "127.0.0.1:9001"), marker.participants());
        assertEquals(List.of("db|collA", "db|collB"), marker.collections());
        assertTrue(marker.preparedAt() > 0);
        assertEquals(Tx2pcLog.Status.PREPARED, Tx2pcLog.status(dtxId));
        Tx2pcLog.deleteParticipantMarker(dtxId);
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_outcome_marker_and_status() throws Exception {
        final var committed = "aaaa1111-0000-0000-0000-000000000000";
        final var aborted = "bbbb2222-0000-0000-0000-000000000000";
        assertEquals(Tx2pcLog.Status.UNKNOWN, Tx2pcLog.status(committed));
        Tx2pcLog.recordOutcome(committed, true);
        Tx2pcLog.recordOutcome(aborted, false);
        assertEquals(Tx2pcLog.Status.COMMITTED, Tx2pcLog.status(committed));
        assertEquals(Tx2pcLog.Status.ABORTED, Tx2pcLog.status(aborted));
        assertTrue(Tx2pcLog.outcomeDtxIds().contains(committed));
        Tx2pcLog.deleteOutcomeMarker(committed);
        assertEquals(Tx2pcLog.Status.UNKNOWN, Tx2pcLog.status(committed));
    }

    @Test
    public void test_garbage_collect_outcomes_drops_aged_keeps_recent() throws Exception {
        final var dtxId = "cccc3333-0000-0000-0000-000000000000";
        Tx2pcLog.recordOutcome(dtxId, true);
        // A retention far in the future keeps it; a negative retention (cutoff in the future) drops it.
        Tx2pcLog.garbageCollectOutcomes(Long.MAX_VALUE / 2);
        assertTrue(Tx2pcLog.outcomeDtxIds().contains(dtxId));
        Tx2pcLog.garbageCollectOutcomes(-1000L);
        assertFalse(Tx2pcLog.outcomeDtxIds().contains(dtxId));
    }

    @Test
    public void test_coordinator_marker_round_trip() throws Exception {
        final var dtxId = "22222222-2222-2222-2222-222222222222";
        assertFalse(Tx2pcLog.isCommitted(dtxId));
        Tx2pcLog.recordCoordinatorCommit(dtxId, List.of("127.0.0.1:9001", "127.0.0.1:9002"));
        assertTrue(Tx2pcLog.isCommitted(dtxId));
        assertTrue(Tx2pcLog.committedDtxIds().contains(dtxId));
        assertEquals(List.of("127.0.0.1:9001", "127.0.0.1:9002"), Tx2pcLog.readCoordinatorParticipants(dtxId));
        Tx2pcLog.deleteCoordinatorMarker(dtxId);
        assertFalse(Tx2pcLog.isCommitted(dtxId));
    }

    @Test
    public void test_missing_markers_read_empty() throws Exception {
        assertEquals(List.of(), Tx2pcLog.readCoordinatorParticipants("no-such-dtx"));
        assertNull(Tx2pcLog.readParticipantMarker("no-such-dtx"));
    }
}
