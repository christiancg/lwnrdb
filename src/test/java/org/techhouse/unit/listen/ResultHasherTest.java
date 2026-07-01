package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.listen.ResultHasher;

public class ResultHasherTest {

    // Same results produce the same hash (deterministic)
    @Test
    public void hash_sameResults_returnsSameHash() {
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("abc"));
        final var results = List.of(obj);

        final var hash1 = ResultHasher.hash(results);
        final var hash2 = ResultHasher.hash(results);

        assertEquals(hash1, hash2);
    }

    // Empty results produce a non-null hash
    @Test
    public void hash_emptyResults_returnsNonNullHash() {
        final var hash = ResultHasher.hash(List.of());

        assertNotNull(hash);
        assertFalse(hash.isBlank());
    }

    // Different results produce different hashes
    @Test
    public void hash_differentResults_returnsDifferentHashes() {
        final var obj1 = new JsonObject();
        obj1.add("_id", new JsonString("abc"));
        final var obj2 = new JsonObject();
        obj2.add("_id", new JsonString("xyz"));

        final var hash1 = ResultHasher.hash(List.of(obj1));
        final var hash2 = ResultHasher.hash(List.of(obj2));

        assertNotEquals(hash1, hash2);
    }

    // Hash is a 64-char lowercase hex string (SHA-256)
    @Test
    public void hash_returnsHexString() {
        final var hash = ResultHasher.hash(List.of());

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    // Order matters: different order → different hash
    @Test
    public void hash_differentOrder_returnsDifferentHashes() {
        final var obj1 = new JsonObject();
        obj1.add("_id", new JsonString("a"));
        final var obj2 = new JsonObject();
        obj2.add("_id", new JsonString("b"));

        final var hash1 = ResultHasher.hash(List.of(obj1, obj2));
        final var hash2 = ResultHasher.hash(List.of(obj2, obj1));

        assertNotEquals(hash1, hash2);
    }
}
