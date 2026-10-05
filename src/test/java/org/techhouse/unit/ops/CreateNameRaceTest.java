package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.admin.DatabaseOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CreateNameRaceTest {
    private static final long SHORT_BUDGET_MS = 100L;
    private static final long LONG_BUDGET_MS = 10_000L;
    private static final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final long originalTimeout = configuration.getTransactionLockTimeoutMs();
    private String heldDb;
    private String heldName;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", LONG_BUDGET_MS);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (heldName != null) {
            releaseNameLock();
        }
        holder.shutdown();
        assertTrue(holder.awaitTermination(5, TimeUnit.SECONDS));
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private <T> T onHolder(Callable<T> action) throws Exception {
        return holder.submit(action).get(30, TimeUnit.SECONDS);
    }

    private void holdNameLock(String dbName, String lockName) throws Exception {
        assertTrue(onHolder(() -> locks.tryLockWrite(dbName, lockName, LONG_BUDGET_MS)));
        heldDb = dbName;
        heldName = lockName;
    }

    private void releaseNameLock() throws Exception {
        onHolder(() -> {
            locks.release(heldDb, heldName);
            return null;
        });
        heldName = null;
    }

    @SuppressWarnings("BusyWait")
    private static Thread startParked(Callable<OperationResponse> action, AtomicReference<OperationResponse> result)
            throws InterruptedException {
        final var thread = new Thread(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        final var deadline = System.currentTimeMillis() + LONG_BUDGET_MS;
        while (thread.getState() != Thread.State.TIMED_WAITING && thread.getState() != Thread.State.WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the create never parked on the registration lock");
            Thread.sleep(5);
        }
        return thread;
    }

    private static OperationResponse createCollection(String collName) {
        return CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, collName));
    }

    private static OperationResponse createDatabase(String dbName) {
        return DatabaseOperationHelper.processCreateDatabaseOperation(new CreateDatabaseRequest(dbName), null);
    }

    @Test
    public void test_a_collection_case_variant_created_concurrently_is_refused() throws Exception {
        holdNameLock(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
        final var raced = new AtomicReference<OperationResponse>();
        final var racer = startParked(() -> createCollection("racedocs"), raced);

        assertEquals(OperationStatus.OK, onHolder(() -> createCollection("RaceDocs")).getStatus());
        releaseNameLock();
        racer.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), raced.get().getErrorCode(),
                "the create that waited must see the variant registered while it waited");
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "RaceDocs"));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "racedocs"));
    }

    @Test
    public void test_a_collection_registration_lock_timeout_answers_409_5() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdNameLock(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);

        final var response = createCollection("timedout");

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "timedout"));
        assertFalse(new File(TestGlobals.PATH + File.separator + TestGlobals.DB + File.separator + "timedout").exists(),
                "a create that never got the registration lock must leave no folder behind");
    }

    @Test
    public void test_a_replicated_collection_create_does_not_take_the_registration_lock() throws Exception {
        holdNameLock(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
        final var request = new CreateCollectionRequest(TestGlobals.DB, "replicated");
        request.setReplicated(true);
        request.setIncarnation(1L);

        final var response = CollectionOperationHelper.processCreateCollectionOperation(request);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "replicated"));
    }

    @Test
    public void test_a_database_case_variant_created_concurrently_is_refused() throws Exception {
        holdNameLock(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK);
        final var raced = new AtomicReference<OperationResponse>();
        final var racer = startParked(() -> createDatabase("raceddb"), raced);

        assertEquals(OperationStatus.OK, onHolder(() -> createDatabase("RacedDb")).getStatus());
        releaseNameLock();
        racer.join(LONG_BUDGET_MS);

        assertEquals(ErrorCode.NAME_COLLIDES_ON_DISK.getCode(), raced.get().getErrorCode(),
                "the create that waited must see the variant registered while it waited");
        assertNotNull(cache.getAdminDbEntry("RacedDb"));
        assertNull(cache.getAdminDbEntry("raceddb"));
    }

    @Test
    public void test_a_database_registration_lock_timeout_answers_409_5() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", SHORT_BUDGET_MS);
        holdNameLock(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK);

        final var response = createDatabase("timeoutdb");

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNull(cache.getAdminDbEntry("timeoutdb"));
        assertFalse(new File(TestGlobals.PATH + File.separator + "timeoutdb").exists(),
                "a create that never got the registration lock must leave no folder behind");
    }

    @Test
    public void test_a_replicated_database_create_does_not_take_the_registration_lock() throws Exception {
        holdNameLock(Globals.ADMIN_DB_NAME, Globals.DATABASE_NAMES_LOCK);
        final var request = new CreateDatabaseRequest("replicadb");
        request.setReplicated(true);

        final var response = DatabaseOperationHelper.processCreateDatabaseOperation(request, null);

        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getAdminDbEntry("replicadb"));
    }
}
