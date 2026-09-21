package org.techhouse.cluster.msg;

public class DigestEntry {
    private String id;
    private String version;
    private boolean deleted;
    private String nodeId;
    private long length;

    public DigestEntry() {
    }

    public DigestEntry(String id, long version, boolean deleted) {
        this(id, version, deleted, null);
    }

    public DigestEntry(String id, long version, boolean deleted, String nodeId) {
        this(id, version, deleted, nodeId, 0L);
    }

    public DigestEntry(String id, long version, boolean deleted, String nodeId, long length) {
        this.id = id;
        this.version = Long.toString(version);
        this.deleted = deleted;
        this.nodeId = nodeId;
        this.length = length;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long versionValue() {
        return version == null ? 0L : Long.parseLong(version);
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
