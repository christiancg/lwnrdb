package org.techhouse.cluster.msg;

import java.util.List;

/**
 * Replicas apply the whole batch within one multi-collection lock window so no other writer interleaves
 * mid-transaction.
 */
public class TxReplicationPayload {
    private List<ReplicationPayload> entries = List.of();

    public TxReplicationPayload() {
    }

    public TxReplicationPayload(List<ReplicationPayload> entries) {
        this.entries = entries == null ? List.of() : entries;
    }

    public List<ReplicationPayload> getEntries() {
        return entries;
    }

    public void setEntries(List<ReplicationPayload> entries) {
        this.entries = entries == null ? List.of() : entries;
    }
}
