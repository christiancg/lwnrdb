package org.techhouse.cluster.msg;

public class DigestEntry {
    private String id;
    private long version;
    private boolean deleted;

    public DigestEntry() {
    }

    public DigestEntry(String id, long version, boolean deleted) {
        this.id = id;
        this.version = version;
        this.deleted = deleted;
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
