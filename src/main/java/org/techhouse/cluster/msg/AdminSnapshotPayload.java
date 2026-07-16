package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;

/**
 * Carrier for the coordinator-authoritative admin snapshot exchanged on {@code ADMIN_SNAPSHOT_ACK}: the
 * responding node's admin epoch plus its full admin state (each database, collection, and user as its stored
 * JSON). A conforming node adopts this snapshot only when its {@code epoch} is higher than the node's own.
 */
public class AdminSnapshotPayload {
    private long epoch;
    private List<JsonObject> databases = List.of();
    private List<JsonObject> collections = List.of();
    private List<JsonObject> users = List.of();
    // Per-collection JSON Schemas, keyed by "db|coll" -> schema object. Only constrained collections
    // appear; an absent key means the collection has no schema. Defaulted (never null) so a peer on an
    // older version that omits the field still deserializes to an empty map rather than null.
    private JsonObject schemas;

    public AdminSnapshotPayload() {
        this.schemas = new JsonObject();
    }

    public AdminSnapshotPayload(long epoch, List<JsonObject> databases, List<JsonObject> collections,
            List<JsonObject> users, JsonObject schemas) {
        this.epoch = epoch;
        this.databases = databases == null ? List.of() : databases;
        this.collections = collections == null ? List.of() : collections;
        this.users = users == null ? List.of() : users;
        this.schemas = schemas == null ? new JsonObject() : schemas;
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
}
