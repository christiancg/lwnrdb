package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminSnapshotConformerCaseVariantTest {
    private static final String OLD_DB = "casedb";
    private static final String NEW_DB = "CaseDb";
    private static final String OLD_COLL = "docs";
    private static final String NEW_COLL = "Docs";
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(conformer, "clusterConfig", config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        return conformer.conform(snapshot, adminEpoch.current());
    }

    private static AdminSnapshotPayload snapshot(List<JsonObject> dbs, List<JsonObject> colls) {
        return new AdminSnapshotPayload(5L, dbs, colls, List.of(), new JsonObject());
    }

    private static JsonObject dbJson(String dbName) {
        return new AdminDbEntry(dbName, new ArrayList<>(), new ArrayList<>()).getData();
    }

    private static JsonObject collJson(String dbName, String collName) {
        final var entry = new AdminCollEntry(dbName, collName, new HashSet<>());
        entry.setIncarnation(100L);
        final var json = entry.getData().deepCopy();
        json.addProperty(Globals.PK_FIELD, Cache.getCollectionIdentifier(dbName, collName));
        return json;
    }

    private void installPopulatedOriginal() throws Exception {
        assertTrue(conform(snapshot(List.of(dbJson(OLD_DB)), List.of(collJson(OLD_DB, OLD_COLL)))));
        final var save = new SaveRequest(OLD_DB, OLD_COLL);
        final var doc = new JsonObject();
        doc.add(Globals.PK_FIELD, new JsonString("predrop"));
        save.setObject(doc);
        save.set_id("predrop");
        IocContainer.get(OperationProcessor.class).processMessage(save);
        assertFalse(cache.getPkIndexAndLoadIfNecessary(OLD_DB, OLD_COLL).isEmpty());
    }

    private List<String> conformRecordingAdminWrites(AdminSnapshotPayload snapshot, List<Boolean> outcome) {
        final var calls = new CopyOnWriteArrayList<String>();
        final Answer<Object> recording = invocation -> {
            final var arguments = invocation.getArguments();
            final var first = arguments.length == 0
                    ? ""
                    : arguments[0] instanceof AdminCollEntry entry
                            ? entry.get_id()
                            : arguments[0] instanceof AdminDbEntry entry
                                    ? entry.get_id()
                                    : String.valueOf(arguments[0]);
            final var second = arguments.length > 1 && arguments[1] instanceof String name ? "|" + name : "";
            calls.add(invocation.getMethod().getName() + ":" + first + second);
            return invocation.callRealMethod();
        };
        try (var ignored = mockStatic(AdminOperationHelper.class, recording)) {
            outcome.add(conform(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return calls;
    }

    private static void assertBefore(List<String> calls, String first, String second) {
        final var firstAt = calls.indexOf(first);
        final var secondAt = calls.indexOf(second);
        assertTrue(firstAt >= 0, first + " never happened: " + calls);
        assertTrue(secondAt >= 0, second + " never happened: " + calls);
        assertTrue(firstAt < secondAt, first + " must precede " + second + ": " + calls);
    }

    @Test
    public void test_a_case_variant_database_quarantines_its_absent_sibling_before_installing() throws Exception {
        installPopulatedOriginal();
        final var outcome = new ArrayList<Boolean>();

        final var calls = conformRecordingAdminWrites(
                snapshot(List.of(dbJson(NEW_DB)), List.of(collJson(NEW_DB, OLD_COLL))), outcome);

        assertTrue(outcome.getFirst());
        assertBefore(calls, "deleteDatabaseEntry:" + OLD_DB, "saveDatabaseEntry:" + NEW_DB);
        assertNull(cache.getAdminDbEntry(OLD_DB));
        assertNotNull(cache.getAdminDbEntry(NEW_DB));
        assertTrue(new File(TestGlobals.PATH + File.separator + NEW_DB, OLD_COLL).isDirectory(),
                "on a case-insensitive volume the late quarantine of the sibling moved this folder aside");
    }

    @Test
    public void test_a_case_variant_collection_quarantines_its_absent_sibling_before_installing() throws Exception {
        installPopulatedOriginal();
        final var outcome = new ArrayList<Boolean>();

        final var calls = conformRecordingAdminWrites(
                snapshot(List.of(dbJson(OLD_DB)), List.of(collJson(OLD_DB, NEW_COLL))), outcome);

        assertTrue(outcome.getFirst());
        assertBefore(calls, "deleteCollectionEntry:" + OLD_DB + "|" + OLD_COLL,
                "saveCollectionEntry:" + Cache.getCollectionIdentifier(OLD_DB, NEW_COLL));
        assertNull(cache.getAdminCollectionEntry(OLD_DB, OLD_COLL));
        assertNotNull(cache.getAdminCollectionEntry(OLD_DB, NEW_COLL));
        assertTrue(new File(TestGlobals.PATH + File.separator + OLD_DB, NEW_COLL).isDirectory());
    }

    @Test
    public void test_a_case_variant_present_in_the_snapshot_skips_the_install() throws Exception {
        installPopulatedOriginal();

        final var complete = conform(snapshot(List.of(dbJson(OLD_DB), dbJson(NEW_DB)),
                List.of(collJson(OLD_DB, OLD_COLL), collJson(OLD_DB, NEW_COLL))));

        assertFalse(complete, "an ambiguous snapshot must not let the round adopt its epoch");
        assertNull(cache.getAdminDbEntry(NEW_DB));
        assertNull(cache.getAdminCollectionEntry(OLD_DB, NEW_COLL));
        assertNotNull(cache.getAdminCollectionEntry(OLD_DB, OLD_COLL));
        assertFalse(cache.getPkIndexAndLoadIfNecessary(OLD_DB, OLD_COLL).isEmpty(), "the sibling is left alone");
    }

    @Test
    public void test_a_name_without_a_case_variant_installs_as_before() throws Exception {
        installPopulatedOriginal();

        assertTrue(conform(snapshot(List.of(dbJson(OLD_DB), dbJson("otherdb")),
                List.of(collJson(OLD_DB, OLD_COLL), collJson("otherdb", "fresh")))));

        assertNotNull(cache.getAdminCollectionEntry("otherdb", "fresh"));
        assertFalse(cache.getPkIndexAndLoadIfNecessary(OLD_DB, OLD_COLL).isEmpty());
    }
}
