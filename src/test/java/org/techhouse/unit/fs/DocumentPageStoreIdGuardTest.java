package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DocumentPageStoreIdGuardTest {
    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static DbEntry entry(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("payload", id + "-value");
        final var dbEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        dbEntry.set_id(id);
        return dbEntry;
    }

    @Test
    public void test_a_stale_offset_is_rejected_rather_than_answered() throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        final var fileSystem = new FileSystem();
        TestUtils.setDbPath(fileSystem, TestGlobals.PATH);
        final var first = fileSystem.insertIntoCollection(entry("aaa"));
        final var second = fileSystem.insertIntoCollection(entry("bbb"));
        final var stale = new PkIndexEntry(first.getDatabaseName(), first.getCollectionName(), first.getValue(),
                second.getPosition(), second.getLength(), second.getPage(), first.getVersion());

        final var failure = assertThrows(IOException.class, () -> fileSystem.getById(stale),
                "a read whose stored _id does not match the index entry must fail, not answer another document");
        assertTrue(failure.getMessage().contains("aaa"), failure.getMessage());
    }

    @Test
    public void test_find_by_id_rejects_a_stale_index_entry_rather_than_answering_another_document() throws Exception {
        final var processor = IocContainer.get(OperationProcessor.class);
        final var cache = IocContainer.get(Cache.class);
        save(processor, "aaa");
        save(processor, "bbb");
        final var pkIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var target = byId(pkIndex, "aaa");
        final var other = byId(pkIndex, "bbb");
        cache.evictEntry(TestGlobals.DB, TestGlobals.COLL, "aaa");
        target.setPosition(other.getPosition());
        target.setLength(other.getLength());
        target.setPage(other.getPage());

        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("aaa");
        final var response = processor.processMessage(request);

        assertNotEquals(OperationStatus.OK, response.getStatus(),
                "a stale offset must surface as an error, never as another document under the requested _id");
    }

    private static PkIndexEntry byId(List<PkIndexEntry> pkIndex, String id) {
        return pkIndex.stream().filter(e -> e.getValue().equals(id)).findFirst().orElseThrow();
    }

    private static void save(OperationProcessor processor, String id) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(entry(id).getData());
        request.set_id(id);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }
}
