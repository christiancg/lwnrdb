package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CreateCollectionDatabaseCheckTest {
    private static final String UNREGISTERED_DB = "unregisteredDb";
    private static final String COLLECTION = "strayColl";
    private final Cache cache = IocContainer.get(Cache.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File unregisteredDatabaseFolder() {
        final var folder = new File(TestGlobals.PATH + File.separator + UNREGISTERED_DB);
        assertTrue(folder.mkdirs());
        return folder;
    }

    private static File pageMetadataFolder() {
        return new File(TestGlobals.PATH + File.separator + Globals.ADMIN_DB_NAME + File.separator
                + Globals.ADMIN_PAGES_FOLDER + File.separator
                + String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, UNREGISTERED_DB, COLLECTION));
    }

    private void assertNothingWritten(File databaseFolder, CreateCollectionRequest request) {
        final var response = processor.processMessage(request);

        assertEquals(OperationStatus.NOT_FOUND, response.getStatus());
        assertEquals(ErrorCode.DATABASE_NOT_FOUND.getCode(), response.getErrorCode());
        assertFalse(new File(databaseFolder, COLLECTION).exists());
        assertFalse(pageMetadataFolder().exists());
        assertNull(cache.getAdminCollectionEntry(UNREGISTERED_DB, COLLECTION));
    }

    @Test
    public void test_create_collection_in_an_unregistered_database_folder_writes_nothing() {
        assertNothingWritten(unregisteredDatabaseFolder(), new CreateCollectionRequest(UNREGISTERED_DB, COLLECTION));
    }

    @Test
    public void test_create_collection_in_a_registered_database_still_succeeds() {
        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, COLLECTION));

        assertEquals(OperationStatus.OK, response.getStatus());
        assertTrue(new File(TestGlobals.PATH + File.separator + TestGlobals.DB, COLLECTION).isDirectory());
    }
}
