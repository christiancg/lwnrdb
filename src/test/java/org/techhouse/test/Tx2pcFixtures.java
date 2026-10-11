package org.techhouse.test;

import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.FindByIdRequest;

public final class Tx2pcFixtures {
    public static final String SELF_ADDRESS = "127.0.0.1:5000";
    private static final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    private Tx2pcFixtures() {
    }

    public static String collectionId() {
        return Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    }

    public static JsonObject document(String id) {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        return obj;
    }

    public static AdminTransactionEntry saveOp(String txId, String clientId, String collName, String id) {
        return new AdminTransactionEntry(txId, clientId, 0, AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB,
                collName, document(id));
    }

    public static void seedPreparedSlice(String dtxId, String id) throws Exception {
        seedPreparedSlice(saveOp(dtxId, "client", TestGlobals.COLL, id), SELF_ADDRESS, List.of(SELF_ADDRESS));
    }

    public static void seedPreparedSlice(AdminTransactionEntry op, String coordinatorAddress, List<String> participants)
            throws Exception {
        AdminOperationHelper.saveTransactionOp(op);
        Tx2pcLog.recordParticipantPrepared(op.getTransactionId(), coordinatorAddress, participants,
                List.of(collectionId()));
    }

    public static OperationStatus findStatus(String id) {
        return findStatus(TestGlobals.COLL, id);
    }

    public static OperationStatus findStatus(String collName, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, collName);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }
}
