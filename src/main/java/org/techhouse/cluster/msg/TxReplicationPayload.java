package org.techhouse.cluster.msg;

import java.util.List;

/**
 * A committed transaction's writes shipped to replicas as one atomic batch: each entry is a single
 * collection's UPSERT group or DELETE group (a collection touched by both upserts and deletes contributes
 * two entries). Replicas apply the whole batch within one multi-collection lock window so no other writer
 * interleaves mid-transaction.
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
