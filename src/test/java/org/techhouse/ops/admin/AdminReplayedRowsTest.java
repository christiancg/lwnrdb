package org.techhouse.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminReplayedRowsTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(AdminReplayedRows.class, "cache", cache);
        TestUtils.standardTearDown();
    }

    private static PkIndexEntry live(String coll, String id) {
        return new PkIndexEntry(Globals.ADMIN_DB_NAME, coll, id, 0L, 10L, 1L, 1L);
    }

    private static PkIndexEntry deleted(String coll, String id) {
        return new PkIndexEntry(Globals.ADMIN_DB_NAME, coll, id, 0L, 0L, 1L, 0L);
    }

    @Test
    public void replayedTransactionRowsArePublishedAndDeletesUnpublished() {
        final var coll = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
        cache.putPkIndexTransaction(live(coll, "gone|1"));

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, coll,
                List.of(live(coll, "tx|localcommit"), deleted(coll, "gone|1")));

        assertNotNull(cache.getPkIndexTransaction("tx|localcommit"));
        assertNull(cache.getPkIndexTransaction("gone|1"));
        cache.removePkIndexTransaction("tx|localcommit");
    }

    @Test
    public void replayedUsageAndTriggerRunRowsArePublished() {
        final var usage = Globals.ADMIN_COLLECTION_USAGE_NAME;
        final var runs = Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME;

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, usage, List.of(live(usage, "u1")));
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, runs, List.of(live(runs, "r1")));

        assertNotNull(cache.getPkIndexCollectionUsage("u1"));
        assertNotNull(cache.getPkIndexTriggerRun("r1"));
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, usage, List.of(deleted(usage, "u1")));
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, runs, List.of(deleted(runs, "r1")));
        assertNull(cache.getPkIndexCollectionUsage("u1"));
        assertNull(cache.getPkIndexTriggerRun("r1"));
    }

    @Test
    public void aReplayedRecordAddsTheDecisionBeforeTheMarkerItReplacesDisappears() throws Exception {
        final var coll = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
        cache.putPkIndexTransaction(live(coll, "tx|part"));
        final var spy = Mockito.spy(cache);
        TestUtils.setPrivateStaticField(AdminReplayedRows.class, "cache", spy);

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, coll,
                List.of(deleted(coll, "tx|part"), live(coll, "tx|localcommit")));

        final var order = Mockito.inOrder(spy);
        order.verify(spy)
                .putPkIndexTransaction(ArgumentMatchers.argThat(row -> "tx|localcommit".equals(row.getValue())));
        order.verify(spy).removePkIndexTransaction("tx|part");
        assertNull(cache.getPkIndexTransaction("tx|part"));
        cache.removePkIndexTransaction("tx|localcommit");
    }

    @Test
    public void theLastReplayedRowOfAnIdWins() {
        final var coll = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, coll, List.of(deleted(coll, "again|1"), live(coll, "again|1"),
                live(coll, "gone|2"), deleted(coll, "gone|2")));

        assertNotNull(cache.getPkIndexTransaction("again|1"));
        assertNull(cache.getPkIndexTransaction("gone|2"));
        cache.removePkIndexTransaction("again|1");
    }

    @Test
    public void replayedUserRecordsRepublishTheCachedUsers() {
        final var coll = Globals.ADMIN_USERS_COLLECTION_NAME;
        TestUtils.createAdmin("replayed_user");
        final var row = cache.getPkIndexAdminUserEntry("replayed_user");
        cache.removeAdminUserEntry("replayed_user");

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, coll, List.of(row));

        assertNotNull(cache.getAdminUserEntry("replayed_user"), "a replayed insert is read back into the cache");
        final var session = clientTracker.addClient(TestUtils.clientSocket());
        clientTracker.setAuthenticatedUser(session, "replayed_user");
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, coll, List.of(deleted(coll, "replayed_user")));
        assertNull(cache.getAdminUserEntry("replayed_user"), "a replayed DELETE_USER can no longer authenticate");
        assertNull(clientTracker.getAuthenticatedUsername(session), "nor keep a session it already opened");
        clientTracker.removeById(session);
        assertNull(cache.getPkIndexAdminUserEntry("replayed_user"));
    }

    @Test
    public void replayedDatabaseAndCollectionDeletesLeaveTheCache() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var collId = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, Globals.ADMIN_COLLECTIONS_COLLECTION_NAME,
                List.of(deleted(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME, collId)));
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME,
                List.of(deleted(Globals.ADMIN_DATABASES_COLLECTION_NAME, TestGlobals.DB)));

        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
    }

    @Test
    public void anUnreadableReplayedRecordAndPageRowsAreLeftToStartup() {
        final var before = cache.getTransactionPkIndexes().size();

        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                List.of(live(Globals.ADMIN_USERS_COLLECTION_NAME, "someone")));
        AdminReplayedRows.publish(Globals.ADMIN_PAGES_DB_NAME, "admin_users", List.of(live("admin_users", "1")));
        AdminReplayedRows.publish(Globals.ADMIN_DB_NAME, Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME, List.of());

        assertNull(cache.getAdminUserEntry("someone"));
        assertEquals(before, cache.getTransactionPkIndexes().size());
    }
}
