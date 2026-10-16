package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.test.TestGlobals;

public class FileSystemHashIndexTest extends FileSystemTestBase {
    @Test
    public void test_hash_index_write_empty_list_is_noop() throws IOException {
        fs.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT, List.of());
        assertNull(fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, "payload", IndexKind.OBJECT));
    }

    @Test
    public void test_hash_index_update_insert_then_remove() throws IOException {
        String fieldName = "payload";

        FieldIndexEntry<String> inserted = new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "cccc3333",
                new HashSet<>(Set.of("id1")));
        fs.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List.of(inserted));
        var index = fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT);
        assertNotNull(index);
        assertEquals(1, index.size());

        fs.appendIndexLog(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT.label(), List.of(), List.of());
        assertNull(fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT));
    }

    @Test
    public void test_drop_index_removes_hash_index_files() throws IOException {
        String fieldName = "payload";
        fs.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "dddd4444", new HashSet<>(Set.of("a")))));
        fs.writeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY, List
                .of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "eeee5555", new HashSet<>(Set.of("b")))));

        assertTrue(fs.dropIndex(TestGlobals.DB, TestGlobals.COLL, fieldName));
        assertNull(fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.OBJECT));
        assertNull(fs.readWholeHashIndexFile(TestGlobals.DB, TestGlobals.COLL, fieldName, IndexKind.ARRAY));
    }
}
