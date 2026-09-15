package org.techhouse.cluster.msg;

public class DigestEntry {
    private String id;
    private long version;
    private boolean deleted;
    private String nodeId;

    public DigestEntry() {
    }

    public DigestEntry(String id, long version, boolean deleted) {
        this(id, version, deleted, null);
    }

    public DigestEntry(String id, long version, boolean deleted, String nodeId) {
        this.id = id;
        this.version = version;
        this.deleted = deleted;
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
