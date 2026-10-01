package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ex.CollectionBusyException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperLockBudgetTest {
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private long originalTimeout;
    private Thread holder;
    private CountDownLatch release;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        IocContainer.get(Cache.class).getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL)
                .setIndexes(Set.of("tag"));
        originalTimeout = configuration.getTransactionLockTimeoutMs();
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", 100L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (release != null) {
            release.countDown();
            holder.join(5000);
        }
        TestUtils.setPrivateField(configuration, "transactionLockTimeoutMs", originalTimeout);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void holdTheCollectionLikeAnIdleTransaction() throws Exception {
        final var taken = new CountDownLatch(1);
        release = new CountDownLatch(1);
        holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                taken.countDown();
                release.await();
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_index_maintenance_gives_up_on_a_collection_an_idle_transaction_holds() throws Exception {
        holdTheCollectionLikeAnIdleTransaction();

        assertThrows(CollectionBusyException.class,
                () -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "any"),
                "a background worker must not park on one collection while every other collection waits behind it");
        assertThrows(CollectionBusyException.class,
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("any")));
    }

    @Test
    public void test_index_maintenance_runs_when_the_collection_is_free() {
        assertDoesNotThrow(() -> IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "missing"));
    }
}
