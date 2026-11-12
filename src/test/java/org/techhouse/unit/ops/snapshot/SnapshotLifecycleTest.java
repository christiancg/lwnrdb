package org.techhouse.unit.ops.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.StartSnapshotResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SnapshotLifecycleTest {
    private final SnapshotTestSupport support = new SnapshotTestSupport();
    private final Configuration configuration = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        support.setUp();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "snapshotMaxBytes", ConfigKey.SNAPSHOT_MAX_BYTES.defaultValue());
        TestUtils.setPrivateField(configuration, "snapshotMaxOpen", ConfigKey.SNAPSHOT_MAX_OPEN.defaultValue());
        support.tearDown();
    }

    private static void assertCode(ErrorCode expected, OperationResponse response) {
        assertEquals(expected.getCode(), response.getErrorCode(), response.getMessage());
    }

    private File snapshotFolder(String snapshotId) {
        return new File(TestGlobals.PATH, Globals.SNAPSHOT_FOLDER_NAME + File.separator + snapshotId);
    }

    @Test
    public void test_start_captures_every_collection_and_release_deletes_its_images() {
        support.save("d0", 0);
        final var response = (StartSnapshotResponse) support
                .send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}");
        assertEquals(List.of(SnapshotTestSupport.COLL, SnapshotTestSupport.OTHER), response.getCollectionNames());
        support.save("d0", 1);
        assertTrue(snapshotFolder(response.getSnapshotId()).isDirectory());

        assertEquals(OperationStatus.OK, support.release(support.client, response.getSnapshotId()).getStatus());

        assertFalse(snapshotFolder(response.getSnapshotId()).exists());
        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND,
                support.read(support.client, response.getSnapshotId(), SnapshotTestSupport.COLL, null, 5));
        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND, support.release(support.client, response.getSnapshotId()));
    }

    @Test
    public void test_another_connection_cannot_read_or_release_it() {
        final var snapshotId = support.start();
        final var other = support.connect();

        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND, support.read(other, snapshotId, SnapshotTestSupport.COLL, null, 5));
        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND, support.release(other, snapshotId));
        assertEquals(OperationStatus.OK,
                support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5).getStatus());
    }

    @Test
    public void test_named_collections_are_the_only_ones_captured() {
        final var response = (StartSnapshotResponse) support
                .send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB
                        + "\",\"collectionNames\":[\"" + SnapshotTestSupport.COLL + "\",\"missing\"]}");

        assertEquals(List.of(SnapshotTestSupport.COLL), response.getCollectionNames());
        final var outside = support.read(support.client, response.getSnapshotId(), SnapshotTestSupport.OTHER, null, 5);
        assertCode(ErrorCode.VALIDATION_ERROR, outside);
        assertTrue(outside.getMessage().contains("is not in snapshot"), outside.getMessage());
    }

    @Test
    public void test_a_missing_database_is_not_found() {
        assertCode(ErrorCode.DATABASE_NOT_FOUND,
                support.send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"noSuchDb\"}"));
    }

    @Test
    public void test_a_read_naming_another_database_is_not_found() {
        final var snapshotId = support.start();

        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND,
                support.send("{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"elsewhere\","
                        + "\"collectionName\":\"coll\",\"snapshotId\":\"" + snapshotId + "\",\"limit\":5}"));
    }

    @Test
    public void test_unreadable_collections_are_left_out() throws Exception {
        final var grants = new HashMap<String, PermissionLevel>();
        grants.put(SnapshotTestSupport.DB + "|" + SnapshotTestSupport.COLL, PermissionLevel.READ);
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry("narrow", "unused", false, Set.of(), new HashMap<>(), grants));
        final var narrow = support.connect();
        IocContainer.get(ClientTracker.class).setAuthenticatedUser(narrow, "narrow");

        final var response = (StartSnapshotResponse) support.send(narrow,
                "{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}");

        assertEquals(List.of(SnapshotTestSupport.COLL), response.getCollectionNames());
    }

    @Test
    public void test_snapshot_operations_are_refused_inside_a_transaction() {
        support.ok("{\"type\":\"START_TRANSACTION\"}");
        try {
            assertCode(ErrorCode.OPERATION_NOT_ALLOWED_IN_TRANSACTION,
                    support.send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}"));
        } finally {
            support.ok("{\"type\":\"ROLLBACK_TRANSACTION\"}");
        }
    }

    @Test
    public void test_passing_snapshot_max_bytes_aborts_the_snapshot_and_the_write_stands() throws Exception {
        TestUtils.setPrivateField(configuration, "snapshotMaxBytes", "1kb");
        support.save("big", "x".repeat(2048));
        final var snapshotId = support.start();

        support.save("big", "after");

        final var read = support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5);
        assertCode(ErrorCode.SNAPSHOT_ABORTED, read);
        assertTrue(read.getMessage().contains(ConfigKey.SNAPSHOT_MAX_BYTES.key()), read.getMessage());
        assertFalse(snapshotFolder(snapshotId).exists());
        final var current = support.cache.getEntriesByIds(SnapshotTestSupport.DB, SnapshotTestSupport.COLL,
                Set.of("big"));
        assertEquals("after", current.getFirst().getData().get("v").asJsonString().getValue());
    }

    @Test
    public void test_dropping_a_held_collection_aborts_the_snapshot() {
        final var snapshotId = support.start();

        support.ok("{\"type\":\"DROP_COLLECTION\",\"databaseName\":\"" + SnapshotTestSupport.DB
                + "\",\"collectionName\":\"" + SnapshotTestSupport.OTHER + "\"}");

        final var read = support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5);
        assertCode(ErrorCode.SNAPSHOT_ABORTED, read);
        assertTrue(read.getMessage().contains("was dropped"), read.getMessage());
    }

    @Test
    public void test_rebuilding_the_primary_key_index_aborts_the_snapshot() {
        support.save("d0", 0);
        final var snapshotId = support.start();

        support.ok("{\"type\":\"REINDEX\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\",\"collectionName\":\""
                + SnapshotTestSupport.COLL + "\",\"primaryKey\":true}");

        final var read = support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5);
        assertCode(ErrorCode.SNAPSHOT_ABORTED, read);
        assertTrue(read.getMessage().contains("primary-key index"), read.getMessage());
    }

    @Test
    public void test_snapshot_max_open_is_enforced_and_a_release_makes_room() throws Exception {
        TestUtils.setPrivateField(configuration, "snapshotMaxOpen", 1);
        final var first = support.start();

        assertCode(ErrorCode.SNAPSHOT_LIMIT_REACHED,
                support.send("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"" + SnapshotTestSupport.DB + "\"}"));
        support.release(support.client, first);
        support.start();
    }

    @Test
    public void test_a_disconnect_releases_every_snapshot_of_the_connection() {
        final var snapshotId = support.start();

        assertEquals(1, support.registry.releaseAllOf(support.client));

        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND,
                support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5));
    }

    @Test
    public void test_an_idle_snapshot_expires_and_a_used_one_does_not() {
        final var idle = support.start();
        final var timeout = TimeUnit.MILLISECONDS.toNanos(configuration.longValue(ConfigKey.SNAPSHOT_IDLE_TIMEOUT_MS));

        assertEquals(0, support.registry.expireIdle(System.nanoTime()));
        assertEquals(1, support.registry.expireIdle(System.nanoTime() + timeout + 1));

        assertCode(ErrorCode.SNAPSHOT_NOT_FOUND, support.read(support.client, idle, SnapshotTestSupport.COLL, null, 5));
    }

    @Test
    public void test_a_snapshot_page_is_a_refusable_read() {
        assertTrue(ResponseBudget.isRefusable(OperationType.READ_SNAPSHOT));
        assertFalse(ResponseBudget.isRefusable(OperationType.START_SNAPSHOT));
    }
}
