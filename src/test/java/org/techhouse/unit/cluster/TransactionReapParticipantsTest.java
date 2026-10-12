package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;

public class TransactionReapParticipantsTest extends RemoteOwnerTxTestBase {

    @Override
    String collectionPrefix() {
        return "reaped-remote-";
    }

    @Test
    public void test_reap_with_participants_goes_through_the_2pc_rollback() {
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = new SaveRequest(TestGlobals.DB, coll);
        save.setObject(document("idle"));
        save.set_id("idle");
        final var relayed = router.forward(save, eJson.toJson(save), true, "admin", clientId);
        assertTrue(relayed != null && relayed.contains("\"OK\""), relayed);
        assertFalse(clientTracker.transactionParticipants(clientId).isEmpty());
        assertFalse(clientTracker.txSessionsSnapshot().isEmpty(), "the owner opened a session for the slice");

        assertTrue(TransactionOperationHelper.reapIdleTransaction(clientId));

        assertNull(clientTracker.getActiveTransaction(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty(), "the owner's slice was aborted");
        assertEquals(OperationStatus.NOT_FOUND, findStatus("idle"));
    }
}
