package org.techhouse.cluster.msg;

/**
 * One row of a collection anti-entropy digest: a document id, its last-write-wins version, and whether that
 * version is a tombstone (a delete) rather than a live document.
 */
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
