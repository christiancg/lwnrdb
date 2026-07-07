package org.techhouse.data.admin;

import java.util.Objects;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

/**
 * A single buffered operation of an in-progress transaction, persisted in the {@code admin/transactions}
 * collection (the durable source of truth replayed at commit). The {@code _id} is {@code
 * transactionId|seq}, so a transaction's operations sort by their apply order and can be located and
 * removed by id at commit/rollback.
 */
public class AdminTransactionEntry extends DbEntry {
    private static final String TRANSACTION_ID_FIELD = "transactionId";
    private static final String CLIENT_ID_FIELD = "clientId";
    private static final String SEQ_FIELD = "seq";
    private static final String OP_TYPE_FIELD = "opType";
    private static final String TARGET_DB_FIELD = "targetDb";
    private static final String TARGET_COLL_FIELD = "targetColl";
    private static final String PAYLOAD_FIELD = "payload";

    public static final String OP_TYPE_SAVE = "SAVE";
    public static final String OP_TYPE_BULK_SAVE = "BULK_SAVE";
    public static final String OP_TYPE_DELETE = "DELETE";

    private String transactionId;
    private String clientId;
    private long seq;
    private String opType;
    private String targetDb;
    private String targetColl;
    private JsonObject payload;

    private AdminTransactionEntry() {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        setData(new JsonObject());
    }

    public AdminTransactionEntry(String transactionId, String clientId, long seq, String opType, String targetDb,
            String targetColl, JsonObject payload) {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME);
        this.transactionId = transactionId;
        this.clientId = clientId;
        this.seq = seq;
        this.opType = opType;
        this.targetDb = targetDb;
        this.targetColl = targetColl;
        this.payload = payload;
        set_id(buildId(transactionId, seq));
        setData(new JsonObject());
        syncData();
    }

    public static String buildId(String transactionId, long seq) {
        return transactionId + Globals.COLL_IDENTIFIER_SEPARATOR + seq;
    }

    public static AdminTransactionEntry fromJsonObject(JsonObject object) {
        final var result = new AdminTransactionEntry();
        result.setData(object);
        result.set_id(object.get(Globals.PK_FIELD).asJsonString().getValue());
        result.transactionId = object.get(TRANSACTION_ID_FIELD).asJsonString().getValue();
        result.clientId = object.get(CLIENT_ID_FIELD).asJsonString().getValue();
        result.seq = Long.parseLong(object.get(SEQ_FIELD).asJsonString().getValue());
        result.opType = object.get(OP_TYPE_FIELD).asJsonString().getValue();
        result.targetDb = object.get(TARGET_DB_FIELD).asJsonString().getValue();
        result.targetColl = object.get(TARGET_COLL_FIELD).asJsonString().getValue();
        result.payload = object.get(PAYLOAD_FIELD).asJsonObject();
        return result;
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(TRANSACTION_ID_FIELD, transactionId);
        data.addProperty(CLIENT_ID_FIELD, clientId);
        data.addProperty(SEQ_FIELD, Long.toString(seq));
        data.addProperty(OP_TYPE_FIELD, opType);
        data.addProperty(TARGET_DB_FIELD, targetDb);
        data.addProperty(TARGET_COLL_FIELD, targetColl);
        data.add(PAYLOAD_FIELD, payload);
    }

    public String getTransactionId() {
        return transactionId;
    }

    public long getSeq() {
        return seq;
    }

    public String getOpType() {
        return opType;
    }

    public String getTargetDb() {
        return targetDb;
    }

    public String getTargetColl() {
        return targetColl;
    }

    public JsonObject getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminTransactionEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return seq == that.seq && Objects.equals(transactionId, that.transactionId)
                && Objects.equals(clientId, that.clientId) && Objects.equals(opType, that.opType)
                && Objects.equals(targetDb, that.targetDb) && Objects.equals(targetColl, that.targetColl)
                && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), transactionId, clientId, seq, opType, targetDb, targetColl, payload);
    }

    @Override
    public String toString() {
        return "AdminTransactionEntry(super=" + super.toString() + ", transactionId=" + transactionId + ", clientId="
                + clientId + ", seq=" + seq + ", opType=" + opType + ", targetDb=" + targetDb + ", targetColl="
                + targetColl + ")";
    }
}
