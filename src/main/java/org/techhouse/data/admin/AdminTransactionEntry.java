package org.techhouse.data.admin;

import java.util.Objects;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

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
    // A transaction id is a UUID and never contains '|', so a record's trailing id token tells a marker
    // ("part"/"coord"/"outcome"/"localcommit") from a numeric slice-op seq.
    public static final String OP_TYPE_PARTICIPANT_PREPARED = "PARTICIPANT_PREPARED";
    public static final String OP_TYPE_COORDINATOR_COMMIT = "COORDINATOR_COMMIT";
    public static final String OP_TYPE_TRANSACTION_OUTCOME = "TRANSACTION_OUTCOME";
    // Written before the first op is applied: present at startup means the commit was decided and must be
    // finished, absent means the transaction was still buffering and the slice is discarded.
    public static final String OP_TYPE_LOCAL_COMMIT = "LOCAL_COMMIT";
    public static final String OP_TYPE_DELETE_TRIGGER_RUN = "DELETE_TRIGGER_RUN";
    public static final String MARKER_PARTICIPANT = "part";
    public static final String MARKER_COORDINATOR = "coord";
    public static final String MARKER_OUTCOME = "outcome";
    public static final String MARKER_LOCAL_COMMIT = "localcommit";

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

    public static AdminTransactionEntry marker(String transactionId, String markerSuffix, String opType,
            JsonObject payload) {
        final var result = new AdminTransactionEntry();
        result.transactionId = transactionId;
        result.clientId = "";
        result.seq = 0;
        result.opType = opType;
        result.targetDb = "";
        result.targetColl = "";
        result.payload = payload;
        result.set_id(transactionId + Globals.COLL_IDENTIFIER_SEPARATOR + markerSuffix);
        result.syncData();
        return result;
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
