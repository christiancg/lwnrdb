package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.tx.SliceCommit;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.test.TransactionRecordCalls;

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
        assertEquals(Tx2pcLog.Status.PREPARED, Tx2pcLog.status(dtxId, null));
        Tx2pcLog.deleteParticipantMarker(dtxId);
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_prepare_writes_ops_and_part_in_one_record() throws Exception {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        for (final var id : List.of("prepared-a", "prepared-b")) {
            final var document = new JsonObject();
            document.add(Globals.PK_FIELD, new JsonString(id));
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(document);
            request.set_id(id);
            TransactionOperationHelper.bufferSave(request, transaction);
        }
        final var dtxId = transaction.getTransactionId().toString();
        final var writes = new ArrayList<List<String>>();

        try (var ignored = Mockito.mockStatic(AdminOperationHelper.class, invocation -> {
            if (TransactionRecordCalls.isWrite(invocation)) {
                writes.add(TransactionRecordCalls.insertsOf(invocation).stream().map(AdminTransactionEntry::get_id)
                        .toList());
            }
            return invocation.callRealMethod();
        })) {
            Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:9000", List.of("127.0.0.1:9000"), List.of("db|coll"),
                    transaction.getPendingOps());
        }

        final var expected = new ArrayList<>(transaction.getBufferedOpIds());
        expected.add(Tx2pcLog.participantMarkerId(dtxId));
        assertEquals(List.of(expected), writes, "the ops and the prepared marker land in one record");
        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertEquals(transaction.getBufferedOpIds(), Tx2pcLog.sliceOpIds(dtxId).stream().sorted().toList());
        assertEquals(2, AdminOperationHelper.readTransactionOps(transaction.getBufferedOpIds()).size());
        AdminOperationHelper.deleteTransactionOps(expected);
        TestUtils.releaseAllLocks();
    }

    @Test
    public void test_outcome_marker_and_status() throws Exception {
        final var committed = "aaaa1111-0000-0000-0000-000000000000";
        final var aborted = "bbbb2222-0000-0000-0000-000000000000";
        assertEquals(Tx2pcLog.Status.NO_RECORD, Tx2pcLog.status(committed, null));
        Tx2pcLog.recordOutcome(committed, true, null);
        Tx2pcLog.recordOutcome(aborted, false, null);
        assertEquals(Tx2pcLog.Status.COMMITTED, Tx2pcLog.status(committed, null));
        assertEquals(Tx2pcLog.Status.ABORTED, Tx2pcLog.status(aborted, null));
        assertTrue(Tx2pcLog.outcomeDtxIds().contains(committed));
        Tx2pcLog.deleteOutcomeMarker(committed);
        assertEquals(Tx2pcLog.Status.NO_RECORD, Tx2pcLog.status(committed, null));
    }

    @Test
    public void test_garbage_collect_outcomes_drops_aged_keeps_recent() throws Exception {
        final var dtxId = "cccc3333-0000-0000-0000-000000000000";
        Tx2pcLog.recordOutcome(dtxId, true, null);
        Tx2pcLog.garbageCollectOutcomes(Long.MAX_VALUE / 2);
        assertTrue(Tx2pcLog.outcomeDtxIds().contains(dtxId));
        Tx2pcLog.garbageCollectOutcomes(-1000L);
        assertFalse(Tx2pcLog.outcomeDtxIds().contains(dtxId));
    }

    @Test
    public void test_coordinator_marker_round_trip() throws Exception {
        final var dtxId = "22222222-2222-2222-2222-222222222222";
        assertFalse(Tx2pcLog.isCommitted(dtxId));
        Tx2pcLog.recordCoordinatorCommit(dtxId, "client-session-1", List.of("127.0.0.1:9001", "127.0.0.1:9002"));
        assertTrue(Tx2pcLog.isCommitted(dtxId));
        assertTrue(Tx2pcLog.committedDtxIds().contains(dtxId));
        assertEquals(List.of("127.0.0.1:9001", "127.0.0.1:9002"), Tx2pcLog.readCoordinatorParticipants(dtxId));
        assertEquals("client-session-1", Tx2pcLog.readCoordinatorSessionId(dtxId));
        Tx2pcLog.deleteCoordinatorMarker(dtxId);
        assertFalse(Tx2pcLog.isCommitted(dtxId));
    }

    @Test
    public void test_coordinator_marker_with_no_session_id_reads_null() throws Exception {
        final var dtxId = "22222222-3333-3333-3333-222222222222";
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of("127.0.0.1:9001"));
        assertNull(Tx2pcLog.readCoordinatorSessionId(dtxId));
    }

    @Test
    public void test_missing_markers_read_empty() throws Exception {
        assertEquals(List.of(), Tx2pcLog.readCoordinatorParticipants("no-such-dtx"));
        assertNull(Tx2pcLog.readCoordinatorSessionId("no-such-dtx"));
        assertNull(Tx2pcLog.readParticipantMarker("no-such-dtx"));
    }

    @Test
    public void test_status_reads_the_decision_after_the_markers_it_answers_from() throws Exception {
        final var dtxId = "dddd4444-0000-0000-0000-000000000000";
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:9000", List.of("127.0.0.1:9001"), List.of("db|collA"));
        try (MockedStatic<Tx2pcLog> log = Mockito.mockStatic(Tx2pcLog.class, Mockito.CALLS_REAL_METHODS)) {
            log.when(() -> Tx2pcLog.isPrepared(dtxId)).thenAnswer(_ -> {
                Tx2pcLog.recordCoordinatorCommit(dtxId, "session", List.of("127.0.0.1:9001"));
                Tx2pcLog.deleteParticipantMarker(dtxId);
                return false;
            });

            assertEquals(Tx2pcLog.Status.COMMITTED, Tx2pcLog.status(dtxId, null));
        }
    }

    @Test
    public void test_status_recheck_reads_the_outcome_marker_too() throws Exception {
        final var dtxId = "eeee5555-0000-0000-0000-000000000000";
        try (MockedStatic<Tx2pcLog> log = Mockito.mockStatic(Tx2pcLog.class, Mockito.CALLS_REAL_METHODS)) {
            log.when(() -> Tx2pcLog.isCommitted(dtxId)).thenReturn(false);
            Tx2pcLog.recordOutcome(dtxId, false, null);

            assertEquals(Tx2pcLog.Status.ABORTED, Tx2pcLog.status(dtxId, null));
        }
    }

    @Test
    public void test_status_prepared_takes_precedence_over_a_late_decision() throws Exception {
        final var dtxId = "ffff6666-0000-0000-0000-000000000000";
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:9000", List.of("127.0.0.1:9000"), List.of("db|collA"));
        try (MockedStatic<Tx2pcLog> log = Mockito.mockStatic(Tx2pcLog.class, Mockito.CALLS_REAL_METHODS)) {
            log.when(() -> Tx2pcLog.isCommitted(dtxId)).thenReturn(false);

            assertEquals(Tx2pcLog.Status.PREPARED, Tx2pcLog.status(dtxId, null));
        }
    }

    @Test
    public void test_status_sees_a_local_commit_written_while_it_read_the_prepare_marker() throws Exception {
        final var dtxId = "77777777-0000-0000-0000-000000000000";
        Tx2pcLog.recordParticipantPrepared(dtxId, "127.0.0.1:9000", List.of("127.0.0.1:9000"), List.of("db|collA"));
        try (MockedStatic<Tx2pcLog> log = Mockito.mockStatic(Tx2pcLog.class, Mockito.CALLS_REAL_METHODS)) {
            log.when(() -> Tx2pcLog.isPrepared(dtxId)).thenAnswer(_ -> {
                SliceCommit.beginFromDurable(dtxId);
                return false;
            });

            assertEquals(Tx2pcLog.Status.COMMITTED, Tx2pcLog.status(dtxId, null),
                    "|localcommit replaced |part while status read it; the slice is decided, not unknown");
        }
    }
}
