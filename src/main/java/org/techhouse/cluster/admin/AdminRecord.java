package org.techhouse.cluster.admin;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.admin.AdminRecordKey;

public class AdminRecord {
    private String key;
    private String version;
    private JsonObject body;

    public AdminRecord() {
    }

    public AdminRecord(AdminRecordKey key, long version, JsonObject body) {
        this.key = key.id();
        this.version = Long.toString(version);
        this.body = body;
    }

    public static AdminRecord tombstone(AdminRecordKey key, long version) {
        return new AdminRecord(key, version, null);
    }

    public AdminRecordKey key() {
        return AdminRecordKey.parse(key);
    }

    public long version() {
        return version == null ? 0L : Long.parseLong(version);
    }

    public JsonObject body() {
        return body;
    }

    public boolean isTombstone() {
        return body == null;
    }

    public boolean outranks(AdminRecord local) {
        if (local == null) {
            return true;
        }
        if (version() != local.version()) {
            return version() > local.version();
        }
        return isTombstone() && !local.isTombstone();
    }
}
