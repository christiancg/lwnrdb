package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;

public class TxReplicationPayloadTest {
    private static ReplicationPayload entry() {
        return new ReplicationPayload("db", "coll", ReplicationOp.UPSERT, List.of(), null, List.of());
    }

    @Test
    public void test_default_entries_is_empty_not_null() {
        assertTrue(new TxReplicationPayload().getEntries().isEmpty());
    }

    @Test
    public void test_null_entries_are_coerced_to_empty() {
        assertTrue(new TxReplicationPayload(null).getEntries().isEmpty());
        final var payload = new TxReplicationPayload(List.of(entry()));
        payload.setEntries(null);
        assertTrue(payload.getEntries().isEmpty());
    }

    @Test
    public void test_entries_round_trip() {
        final var payload = new TxReplicationPayload();
        payload.setEntries(List.of(entry(), entry()));
        assertEquals(2, payload.getEntries().size());
    }
}
