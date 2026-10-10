package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.CacheableResource;

public class CacheableResourceTest {

    @Test
    public void test_record_accessors() {
        final var r = new CacheableResource(AccessKind.FIELD_INDEX, "db", "coll", "field", 1024L);
        assertEquals(AccessKind.FIELD_INDEX, r.kind());
        assertEquals("db", r.dbName());
        assertEquals("coll", r.collName());
        assertEquals("field", r.indexKey());
        assertEquals(1024L, r.estimatedSizeBytes());
    }

    @Test
    public void test_equals_and_hashCode() {
        final var r1 = new CacheableResource(AccessKind.PK_INDEX, "db", "coll", null, 0L);
        final var r2 = new CacheableResource(AccessKind.PK_INDEX, "db", "coll", null, 0L);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test
    public void test_indexed_field_of_strips_the_type_suffix() {
        assertEquals("status", CacheableResource.indexedFieldOf("status|String"));
    }

    @Test
    public void test_indexed_field_of_a_hash_key() {
        assertEquals("tags",
                CacheableResource.indexedFieldOf(org.techhouse.cache.Cache.getHashIndexIdentifier("tags", "Array")));
    }

    @Test
    public void test_indexed_field_of_a_key_without_separator() {
        assertEquals("plain", CacheableResource.indexedFieldOf("plain"));
    }
}
