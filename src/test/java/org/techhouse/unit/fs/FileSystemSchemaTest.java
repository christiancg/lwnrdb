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
    void resetSchema() throws Exception {
        fs.metadataFiles().deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_read_absent_schema_returns_null() throws Exception {
        assertNull(fs.metadataFiles().readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_write_then_read() throws Exception {
        final var json = "{\"type\":\"object\"}";
        fs.metadataFiles().writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, json);
        assertEquals(json, fs.metadataFiles().readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_overwrite() throws Exception {
        fs.metadataFiles().writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"object\"}");
        fs.metadataFiles().writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"string\"}");
        assertEquals("{\"type\":\"string\"}",
                fs.metadataFiles().readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_delete() throws Exception {
        fs.metadataFiles().writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"object\"}");
        assertTrue(fs.metadataFiles().deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(fs.metadataFiles().readCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(fs.metadataFiles().deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }
}
