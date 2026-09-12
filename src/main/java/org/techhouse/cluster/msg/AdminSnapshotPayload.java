package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;

public class AdminSnapshotPayload {
    private long epoch;
    private List<JsonObject> databases = List.of();
    private List<JsonObject> collections = List.of();
    private List<JsonObject> users = List.of();
    // Defaulted (never null) so a peer on an older version that omits the field still deserializes to an
    // empty map rather than null.
    private JsonObject schemas;
    private JsonObject procedures;
    private JsonObject triggers;
    private JsonObject schedules;

    public AdminSnapshotPayload() {
        this.schemas = new JsonObject();
        this.procedures = new JsonObject();
        this.triggers = new JsonObject();
        this.schedules = new JsonObject();
    }

    public AdminSnapshotPayload(long epoch, List<JsonObject> databases, List<JsonObject> collections,
            List<JsonObject> users, JsonObject schemas) {
        this(epoch, databases, collections, users, schemas, new JsonObject(), new JsonObject(), new JsonObject());
    }

    public AdminSnapshotPayload(long epoch, List<JsonObject> databases, List<JsonObject> collections,
            List<JsonObject> users, JsonObject schemas, JsonObject procedures, JsonObject triggers) {
        this(epoch, databases, collections, users, schemas, procedures, triggers, new JsonObject());
    }

    public AdminSnapshotPayload(long epoch, List<JsonObject> databases, List<JsonObject> collections,
            List<JsonObject> users, JsonObject schemas, JsonObject procedures, JsonObject triggers,
            JsonObject schedules) {
        this.epoch = epoch;
        this.databases = databases == null ? List.of() : databases;
        this.collections = collections == null ? List.of() : collections;
        this.users = users == null ? List.of() : users;
        this.schemas = schemas == null ? new JsonObject() : schemas;
        this.procedures = procedures == null ? new JsonObject() : procedures;
        this.triggers = triggers == null ? new JsonObject() : triggers;
        this.schedules = schedules == null ? new JsonObject() : schedules;
    }

    public long getEpoch() {
        return epoch;
    }

    public void setEpoch(long epoch) {
        this.epoch = epoch;
    }

    public List<JsonObject> getDatabases() {
        return databases;
    }

    public void setDatabases(List<JsonObject> databases) {
        this.databases = databases == null ? List.of() : databases;
    }

    public List<JsonObject> getCollections() {
        return collections;
    }

    public void setCollections(List<JsonObject> collections) {
        this.collections = collections == null ? List.of() : collections;
    }

    public List<JsonObject> getUsers() {
        return users;
    }

    public void setUsers(List<JsonObject> users) {
        this.users = users == null ? List.of() : users;
    }

    public JsonObject getSchemas() {
        return schemas;
    }

    public void setSchemas(JsonObject schemas) {
        this.schemas = schemas == null ? new JsonObject() : schemas;
    }

    public JsonObject getProcedures() {
        return procedures;
    }

    public void setProcedures(JsonObject procedures) {
        this.procedures = procedures == null ? new JsonObject() : procedures;
    }

    public JsonObject getTriggers() {
        return triggers;
    }

    public void setTriggers(JsonObject triggers) {
        this.triggers = triggers == null ? new JsonObject() : triggers;
    }

    public JsonObject getSchedules() {
        return schedules;
    }

    public void setSchedules(JsonObject schedules) {
        this.schedules = schedules == null ? new JsonObject() : schedules;
    }
}
