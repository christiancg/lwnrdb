package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DropAfterPartialDropTest {
    private static final String UNREGISTERED_COLL = "neverCreated";
    private static final String DELETE_COLLECTION_ENTRY = "deleteCollectionEntry";

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

    private static File databaseFolder() {
        return new File(TestGlobals.PATH, TestGlobals.DB);
    }

    private static File collectionFolder() {
        return new File(databaseFolder(), TestGlobals.COLL);
    }

    private OperationStatus dropCollection(String collName) {
        return processor.processMessage(new DropCollectionRequest(TestGlobals.DB, collName)).getStatus();
    }

    private OperationStatus dropDatabase() {
        return processor.processMessage(new DropDatabaseRequest(TestGlobals.DB)).getStatus();
    }

    @Test
    public void drop_collection_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(collectionFolder());

        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL),
                "the collection must no longer be registered");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, TestGlobals.COLL)).getStatus());
    }

    @Test
    public void drop_database_of_a_registered_name_with_no_folder_unregisters_it() {
        TestUtils.deleteFolder(databaseFolder());

        assertEquals(OperationStatus.OK, dropDatabase());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB), "the database must no longer be registered");
        assertEquals(OperationStatus.OK,
                processor.processMessage(new CreateDatabaseRequest(TestGlobals.DB)).getStatus());
    }

    @Test
    public void drop_of_an_unregistered_name_with_no_folder_is_still_refused() {
        assertEquals(OperationStatus.ERROR, dropCollection(UNREGISTERED_COLL));
    }

    @Test
    public void drop_collection_after_a_failed_admin_write_can_be_retried() {
        final var failuresLeft = new AtomicInteger(1);
        try (var ignored = mockStatic(AdminOperationHelper.class, invocation -> {
            if (DELETE_COLLECTION_ENTRY.equals(invocation.getMethod().getName())
                    && failuresLeft.getAndDecrement() > 0) {
                throw new IOException("disk full");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.COLL));
            assertFalse(collectionFolder().exists(), "the first attempt deleted the folder before failing");
            assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL),
                    "the failed attempt left the collection registered");

            assertEquals(OperationStatus.OK, dropCollection(TestGlobals.COLL));
            assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        }
    }
}
