package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FileSystemSchemaTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void resetSchema() {
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    // Reading a schema for a collection that has none returns null
    @Test
    public void test_read_absent_schema_returns_null() throws Exception {
        assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // Write then read round-trips the schema JSON
    @Test
    public void test_write_then_read() throws Exception {
        final var json = "{\"type\":\"object\"}";
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json);
        assertEquals(json, fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // Writing again replaces the previous schema
    @Test
    public void test_overwrite() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"object\"}");
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"string\"}");
        assertEquals("{\"type\":\"string\"}", fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    // Delete removes an existing schema and reports success; a second delete reports false
    @Test
    public void test_delete() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"object\"}");
        assertTrue(fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(fs.readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }
}
