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

    public AdminSnapshotPayload() {
    }

    public AdminSnapshotPayload(long epoch, List<JsonObject> databases, List<JsonObject> collections,
            List<JsonObject> users) {
        this.epoch = epoch;
        this.databases = databases == null ? List.of() : databases;
        this.collections = collections == null ? List.of() : collections;
        this.users = users == null ? List.of() : users;
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
}
