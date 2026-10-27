package org.techhouse.cluster.msg;

import java.util.List;
import org.techhouse.ejson.NullsOmitted;
import org.techhouse.ejson.elements.JsonObject;

@NullsOmitted
public class AntiEntropyPayload {
    private String dbName;
    private String collName;
    private List<DigestEntry> digest;
    private List<String> ids;
    private List<JsonObject> documents;
    private List<String> versions;
    private String summary;
    private boolean summaryMatch;
    private String incarnation;
    private boolean staleIncarnation;
    private int bucketCount;
    private List<Integer> buckets;
    private List<String> bucketSummaries;
    private String nodeId;

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

    public List<String> getVersions() {
        return versions;
    }

    public void setVersions(List<String> versions) {
        this.versions = versions;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isSummaryMatch() {
        return summaryMatch;
    }

    public void setSummaryMatch(boolean summaryMatch) {
        this.summaryMatch = summaryMatch;
    }

    public String getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(String incarnation) {
        this.incarnation = incarnation;
    }

    public long incarnationValue() {
        return incarnation == null ? 0L : Long.parseLong(incarnation);
    }

    public void setIncarnationValue(long value) {
        this.incarnation = Long.toString(value);
    }

    public boolean isStaleIncarnation() {
        return staleIncarnation;
    }

    public void setStaleIncarnation(boolean staleIncarnation) {
        this.staleIncarnation = staleIncarnation;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = bucketCount;
    }

    public List<Integer> getBuckets() {
        return buckets;
    }

    public void setBuckets(List<Integer> buckets) {
        this.buckets = buckets;
    }

    public List<String> getBucketSummaries() {
        return bucketSummaries;
    }

    public void setBucketSummaries(List<String> bucketSummaries) {
        this.bucketSummaries = bucketSummaries;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }
}
