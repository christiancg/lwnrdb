package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;

/**
 * Carrier for the anti-entropy exchange. A {@code DIGEST} request sets {@code dbName}/{@code collName}; its
 * {@code DIGEST_ACK} reply fills {@code digest}. A {@code PULL} request additionally sets {@code ids}; its
 * {@code PULL_ACK} reply fills {@code documents} and their aligned {@code versions}.
 */
public class AntiEntropyPayload {
    private String dbName;
    private String collName;
    private List<DigestEntry> digest;
    private List<String> ids;
    private List<JsonObject> documents;
    private List<Long> versions;

    public AntiEntropyPayload() {
    }

    public AntiEntropyPayload(String dbName, String collName) {
        this.dbName = dbName;
        this.collName = collName;
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

    public List<DigestEntry> getDigest() {
        return digest;
    }

    public void setDigest(List<DigestEntry> digest) {
        this.digest = digest;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    public List<JsonObject> getDocuments() {
        return documents;
    }

    public void setDocuments(List<JsonObject> documents) {
        this.documents = documents;
    }

    public List<Long> getVersions() {
        return versions;
    }

    public void setVersions(List<Long> versions) {
        this.versions = versions;
    }
}
