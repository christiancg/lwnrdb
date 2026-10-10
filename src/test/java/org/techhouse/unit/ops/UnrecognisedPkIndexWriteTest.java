package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class UnrecognisedPkIndexWriteTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File pkIndexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    private OperationStatus save(String id) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", "value-" + id);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private OperationStatus find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private byte[] plantOldGrammarPkIndex() throws Exception {
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
        Files.writeString(pkIndexFile().toPath(), "first|0|20|0|0\nsecond|20|20|0|0\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        return Files.readAllBytes(pkIndexFile().toPath());
    }

    @Test
    public void test_a_save_into_a_collection_with_an_unrecognised_pk_index_is_refused() throws Exception {
        assertEquals(OperationStatus.OK, save("first"));
        final var planted = plantOldGrammarPkIndex();

        assertEquals(OperationStatus.ERROR, save("third"));
        assertEquals(OperationStatus.ERROR, save("first"));

        assertArrayEquals(planted, Files.readAllBytes(pkIndexFile().toPath()));
    }

    @Test
    public void test_a_read_of_an_unrecognised_pk_index_is_an_error_not_an_absence() throws Exception {
        assertEquals(OperationStatus.OK, save("first"));
        final var planted = plantOldGrammarPkIndex();

        assertEquals(OperationStatus.ERROR, find("first"));
        assertEquals(OperationStatus.ERROR, find("missing"));

        assertArrayEquals(planted, Files.readAllBytes(pkIndexFile().toPath()));
    }
}
