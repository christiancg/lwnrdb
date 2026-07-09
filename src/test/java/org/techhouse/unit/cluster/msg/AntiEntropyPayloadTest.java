package org.techhouse.unit.cluster.msg;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.DigestEntry;
import org.techhouse.ejson.elements.JsonObject;

import static org.junit.jupiter.api.Assertions.*;

public class AntiEntropyPayloadTest {

    @Test
    public void test_digest_entry_accessors() {
        final var entry = new DigestEntry("a", 42L, true);
        assertEquals("a", entry.getId());
        assertEquals(42L, entry.getVersion());
        assertTrue(entry.isDeleted());

        final var blank = new DigestEntry();
        blank.setId("b");
        blank.setVersion(7L);
        blank.setDeleted(false);
        assertEquals("b", blank.getId());
        assertEquals(7L, blank.getVersion());
        assertFalse(blank.isDeleted());
    }

    @Test
    public void test_payload_accessors() {
        final var payload = new AntiEntropyPayload("db", "coll");
        assertEquals("db", payload.getDbName());
        assertEquals("coll", payload.getCollName());

        payload.setDbName("db2");
        payload.setCollName("coll2");
        payload.setDigest(List.of(new DigestEntry("a", 1L, false)));
        payload.setIds(List.of("a"));
        payload.setDocuments(List.of(new JsonObject()));
        payload.setVersions(List.of(1L));

        assertEquals("db2", payload.getDbName());
        assertEquals("coll2", payload.getCollName());
        assertEquals(1, payload.getDigest().size());
        assertEquals(1, payload.getIds().size());
        assertEquals(1, payload.getDocuments().size());
        assertEquals(1, payload.getVersions().size());

        final var blank = new AntiEntropyPayload();
        blank.setDbName("d");
        assertEquals("d", blank.getDbName());
    }
}
