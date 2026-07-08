package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;

public class ReplicationPayload {
    private String dbName;
    private String collName;
    private ReplicationOp op;
    private List<JsonObject> documents;
    private List<String> ids;

    public ReplicationPayload() {
    }

    public ReplicationPayload(String dbName, String collName, ReplicationOp op, List<JsonObject> documents,
            List<String> ids) {
        this.dbName = dbName;
        this.collName = collName;
        this.op = op;
        this.documents = documents;
        this.ids = ids;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getCollName() {
        return collName;
    }

    public void setCollName(String collName) {
        this.collName = collName;
    }

    public ReplicationOp getOp() {
        return op;
    }

    public void setOp(ReplicationOp op) {
        this.op = op;
    }

    public List<JsonObject> getDocuments() {
        return documents;
    }

    public void setDocuments(List<JsonObject> documents) {
        this.documents = documents;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }
}
