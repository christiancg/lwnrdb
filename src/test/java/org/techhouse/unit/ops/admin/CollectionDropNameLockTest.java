package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.CollectionOperationHelper;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CollectionDropNameLockTest {
    private static final String CASE_VARIANT = "MyCollection";
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private final ExecutorService other = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        other.shutdownNow();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static OperationResponse drop() {
        return CollectionOperationHelper
                .processDropCollectionOperation(new DropCollectionRequest(TestGlobals.DB, TestGlobals.COLL));
    }

    private static OperationResponse createCaseVariant() {
        return CollectionOperationHelper
                .processCreateCollectionOperation(new CreateCollectionRequest(TestGlobals.DB, CASE_VARIANT));
    }

    @Test
    public void test_a_case_variant_create_waits_for_the_drop_to_finish() throws Exception {
        final var createdDuringDrop = new AtomicBoolean();
        final var create = new AtomicReference<CompletableFuture<OperationResponse>>();
        try (var admin = mockStatic(AdminOperationHelper.class, CALLS_REAL_METHODS)) {
            admin.when(() -> AdminOperationHelper.deletePageCollections(eq(TestGlobals.DB), eq(TestGlobals.COLL)))
                    .thenAnswer(invocation -> {
                        create.set(CompletableFuture.supplyAsync(CollectionDropNameLockTest::createCaseVariant, other));
                        Thread.sleep(300);
                        createdDuringDrop.set(create.get().isDone());
                        return invocation.callRealMethod();
                    });

            assertEquals(OperationStatus.OK, drop().getStatus());
        }

        assertFalse(createdDuringDrop.get(),
                "a case-variant create landing between the unregistration and the page-row drop shares its folder");
        assertEquals(OperationStatus.OK, create.get().get(10, TimeUnit.SECONDS).getStatus());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, CASE_VARIANT));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_drop_that_cannot_take_the_names_lock_answers_409_5_and_deletes_nothing() throws Exception {
        final var originalLockTimeout = configuration.getTransactionLockTimeoutMs();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 100L);
        final OperationResponse response;
        try {
            response = dropWhileAnotherThreadHoldsTheNamesLock();
        } finally {
            TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalLockTimeout);
        }

        assertEquals(ErrorCode.TRANSACTION_LOCK_TIMEOUT.getCode(), response.getErrorCode());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL));
    }

    private OperationResponse dropWhileAnotherThreadHoldsTheNamesLock() throws Exception {
        final var held = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var holder = other.submit(() -> {
            locks.tryLockWrite(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK, 1_000);
            held.countDown();
            release.await();
            locks.release(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
            return null;
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));

        final var response = drop();
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        return response;
    }

    @Test
    public void test_a_drop_releases_the_names_lock() throws Exception {
        assertEquals(OperationStatus.OK, drop().getStatus());

        final var acquiredElsewhere = other.submit(() -> {
            final var acquired = locks.tryLockWrite(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK, 0);
            if (acquired) {
                locks.release(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
            }
            return acquired;
        });

        assertTrue(acquiredElsewhere.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_a_drop_of_an_unregistered_collection_releases_the_names_lock() throws Exception {
        final var response = CollectionOperationHelper
                .processDropCollectionOperation(new DropCollectionRequest(TestGlobals.DB, "neverCreated"));

        assertEquals(ErrorCode.ERROR_DROPPING_COLLECTION.getCode(), response.getErrorCode());
        final var acquiredElsewhere = other.submit(() -> {
            final var acquired = locks.tryLockWrite(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK, 0);
            if (acquired) {
                locks.release(TestGlobals.DB, Globals.COLLECTION_NAMES_LOCK);
            }
            return acquired;
        });
        assertTrue(acquiredElsewhere.get(5, TimeUnit.SECONDS));
    }
}
