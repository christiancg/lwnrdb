package org.techhouse.data.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

/**
 * One pending trigger run, persisted in {@code admin/trigger_runs} before its events are queued and consumed
 * inside the transaction that applies the run's effects, so a run cannot be both applied and replayed.
 *
 * <p>
 * The {@code _id} is {@code runId|chunkSeq}: a run whose id list would not fit in one record under
 * {@code maxEntrySize} is split across chunks that share the runId. {@code ids} is stored for CREATED/UPDATED
 * (the dispatcher re-reads the committed documents, as {@code TriggerHelper.afterWriteIds} does) and
 * {@code documents} for DELETED, where the document no longer exists to re-read.
 */
public class AdminTriggerRunEntry extends DbEntry {
    private static final String RUN_ID_FIELD = "runId";
    private static final String NODE_ID_FIELD = "nodeId";
    private static final String DB_NAME_FIELD = "dbName";
    private static final String COLL_NAME_FIELD = "collName";
    private static final String TRIGGER_NAME_FIELD = "triggerName";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String EVENT_TYPE_FIELD = "eventType";
    private static final String BATCH_MODE_FIELD = "batchMode";
    private static final String ACTING_USER_FIELD = "actingUser";
    private static final String DEPTH_FIELD = "depth";
    private static final String FIRED_AT_FIELD = "firedAt";
    private static final String IDS_FIELD = "ids";
    private static final String DOCUMENTS_FIELD = "documents";
    private static final String STATUS_FIELD = "status";
    private static final String ATTEMPTS_FIELD = "attempts";
    private static final String LAST_ERROR_FIELD = "lastError";
    private static final String LAST_ERROR_AT_FIELD = "lastErrorAt";
    private static final String NEXT_ATTEMPT_AT_FIELD = "nextAttemptAt";

    private String runId;
    private String nodeId;
    private String dbName;
    private String collName;
    private String triggerName;
    private String procedureName;
    private EventType eventType;
    private boolean batchMode;
    private String actingUser;
    private int depth;
    private long firedAt;
    private List<String> ids;
    private List<JsonObject> documents;
    private TriggerRunStatus status = TriggerRunStatus.PENDING;
    private int attempts;
    private String lastError;
    private long lastErrorAt;
    private long nextAttemptAt;

    private AdminTriggerRunEntry() {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        setData(new JsonObject());
        this.ids = new ArrayList<>();
        this.documents = new ArrayList<>();
    }

    public AdminTriggerRunEntry(String runId, long chunkSeq, String nodeId, String dbName, String collName,
            String triggerName, String procedureName, EventType eventType, boolean batchMode, String actingUser,
            int depth, long firedAt, List<String> ids, List<JsonObject> documents) {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        this.runId = runId;
        this.nodeId = nodeId;
        this.dbName = dbName;
        this.collName = collName;
        this.triggerName = triggerName;
        this.procedureName = procedureName;
        this.eventType = eventType;
        this.batchMode = batchMode;
        this.actingUser = actingUser;
        this.depth = depth;
        this.firedAt = firedAt;
        this.ids = ids == null ? new ArrayList<>() : ids;
        this.documents = documents == null ? new ArrayList<>() : documents;
        set_id(buildId(runId, chunkSeq));
        setData(new JsonObject());
        syncData();
    }

    public static String buildId(String runId, long chunkSeq) {
        return runId + Globals.COLL_IDENTIFIER_SEPARATOR + chunkSeq;
    }

    public static String runIdOf(String recordId) {
        final var sep = recordId.lastIndexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
        return sep > 0 ? recordId.substring(0, sep) : recordId;
    }

    public static AdminTriggerRunEntry fromJsonObject(JsonObject object) {
        final var result = new AdminTriggerRunEntry();
        result.setData(object);
        result.set_id(object.get(Globals.PK_FIELD).asJsonString().getValue());
        result.runId = readString(object, RUN_ID_FIELD);
        result.nodeId = readString(object, NODE_ID_FIELD);
        result.dbName = readString(object, DB_NAME_FIELD);
        result.collName = readString(object, COLL_NAME_FIELD);
        result.triggerName = readString(object, TRIGGER_NAME_FIELD);
        result.procedureName = readString(object, PROCEDURE_NAME_FIELD);
        result.eventType = EventType.valueOf(readString(object, EVENT_TYPE_FIELD));
        result.batchMode = Boolean.parseBoolean(readString(object, BATCH_MODE_FIELD));
        result.actingUser = readString(object, ACTING_USER_FIELD);
        result.depth = intOrZero(readString(object, DEPTH_FIELD));
        result.firedAt = longOrZero(readString(object, FIRED_AT_FIELD));
        result.ids = new ArrayList<>();
        if (object.has(IDS_FIELD)) {
            for (final var element : object.get(IDS_FIELD).asJsonArray().asList()) {
                result.ids.add(element.asJsonString().getValue());
            }
        }
        result.documents = new ArrayList<>();
        if (object.has(DOCUMENTS_FIELD)) {
            for (final var element : object.get(DOCUMENTS_FIELD).asJsonArray().asList()) {
                result.documents.add(element.asJsonObject());
            }
        }
        // A record written before retries existed carries none of these fields and reads as a first,
        // still-pending attempt - which is exactly what it is.
        result.status = statusOf(readString(object, STATUS_FIELD));
        result.attempts = intOrZero(readString(object, ATTEMPTS_FIELD));
        result.lastError = readString(object, LAST_ERROR_FIELD);
        result.lastErrorAt = longOrZero(readString(object, LAST_ERROR_AT_FIELD));
        result.nextAttemptAt = longOrZero(readString(object, NEXT_ATTEMPT_AT_FIELD));
        return result;
    }

    private static TriggerRunStatus statusOf(String value) {
        if (value == null) {
            return TriggerRunStatus.PENDING;
        }
        try {
            return TriggerRunStatus.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return TriggerRunStatus.PENDING;
        }
    }

    // An unauthenticated write has no acting user, so a field can legitimately round-trip as JSON null.
    private static String readString(JsonObject object, String field) {
        if (!object.has(field) || !(object.get(field) instanceof JsonString value)) {
            return null;
        }
        return value.getValue();
    }

    // A record written by an older node - or a torn one - can be missing a numeric field; a run replayed at
    // depth 0 with an unknown fire time is better than one that cannot be read back at all.
    private static int intOrZero(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static long longOrZero(String value) {
        return value == null ? 0L : Long.parseLong(value);
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(RUN_ID_FIELD, runId);
        data.addProperty(NODE_ID_FIELD, nodeId);
        data.addProperty(DB_NAME_FIELD, dbName);
        data.addProperty(COLL_NAME_FIELD, collName);
        data.addProperty(TRIGGER_NAME_FIELD, triggerName);
        data.addProperty(PROCEDURE_NAME_FIELD, procedureName);
        data.addProperty(EVENT_TYPE_FIELD, eventType.name());
        data.addProperty(BATCH_MODE_FIELD, Boolean.toString(batchMode));
        data.addProperty(ACTING_USER_FIELD, actingUser);
        data.addProperty(DEPTH_FIELD, Integer.toString(depth));
        data.addProperty(FIRED_AT_FIELD, Long.toString(firedAt));
        final var idArray = new JsonArray();
        for (final var id : ids) {
            idArray.add(new JsonString(id));
        }
        data.add(IDS_FIELD, idArray);
        final var documentArray = new JsonArray();
        for (final var document : documents) {
            documentArray.add(document);
        }
        data.add(DOCUMENTS_FIELD, documentArray);
        data.addProperty(STATUS_FIELD, status.name());
        data.addProperty(ATTEMPTS_FIELD, Integer.toString(attempts));
        data.addProperty(LAST_ERROR_FIELD, lastError);
        data.addProperty(LAST_ERROR_AT_FIELD, Long.toString(lastErrorAt));
        data.addProperty(NEXT_ATTEMPT_AT_FIELD, Long.toString(nextAttemptAt));
    }

    /** Records the outcome of one attempt, leaving the run replayable or marking it dead. */
    public void markAttempt(TriggerRunStatus newStatus, int attemptCount, String error, long nextAttempt) {
        this.status = newStatus;
        this.attempts = attemptCount;
        this.lastError = error;
        this.lastErrorAt = error == null ? lastErrorAt : System.currentTimeMillis();
        this.nextAttemptAt = nextAttempt;
        syncData();
    }

    public TriggerRunStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public long getLastErrorAt() {
        return lastErrorAt;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public EventType getEventType() {
        return eventType;
    }

    public boolean isBatchMode() {
        return batchMode;
    }

    public String getActingUser() {
        return actingUser;
    }

    public int getDepth() {
        return depth;
    }

    public long getFiredAt() {
        return firedAt;
    }

    public List<String> getIds() {
        return ids;
    }

    public List<JsonObject> getDocuments() {
        return documents;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminTriggerRunEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return batchMode == that.batchMode && depth == that.depth && firedAt == that.firedAt
                && Objects.equals(runId, that.runId) && Objects.equals(nodeId, that.nodeId)
                && Objects.equals(dbName, that.dbName) && Objects.equals(collName, that.collName)
                && Objects.equals(triggerName, that.triggerName) && Objects.equals(procedureName, that.procedureName)
                && eventType == that.eventType && Objects.equals(actingUser, that.actingUser)
                && Objects.equals(ids, that.ids) && Objects.equals(documents, that.documents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), runId, nodeId, dbName, collName, triggerName, procedureName, eventType,
                batchMode, actingUser, depth, firedAt, ids, documents);
    }

    @Override
    public String toString() {
        return "AdminTriggerRunEntry(super=" + super.toString() + ", runId=" + runId + ", nodeId=" + nodeId
                + ", dbName=" + dbName + ", collName=" + collName + ", triggerName=" + triggerName + ", eventType="
                + eventType + ", batchMode=" + batchMode + ", ids=" + ids.size() + ", documents=" + documents.size()
                + ")";
    }
}
