package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.test.TestGlobals;

public class UnversionedRecordsTest extends AdminRecordTestBase {

    @Test
    public void test_legacy_definitions_are_stamped_once_and_keep_their_content() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "legacy", "{\"name\":\"legacy\",\"source\":\"return 1;\"}");
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, "{\"type\":\"object\"}");

        UnversionedRecords.stampAll();

        final var procedure = Objects
                .requireNonNull(AdminRecords.live(AdminRecordKey.procedure(TestGlobals.DB, "legacy")));
        assertTrue(procedure.version() > AdminStamp.BOOTSTRAP_VERSION);
        assertEquals("return 1;", procedure.body().get("source").asJsonString().getValue());
        final var schema = StoredDefinitions.schema(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(AdminStamp.versionOf(schema) > AdminStamp.BOOTSTRAP_VERSION);
        assertEquals("object", AdminStamp.unwrappedSchema(schema).get("type").asJsonString().getValue());
        final var stamped = procedure.version();

        UnversionedRecords.stampAll();

        assertEquals(stamped, liveVersion(AdminRecordKey.procedure(TestGlobals.DB, "legacy")));
    }

    @Test
    public void test_a_legacy_row_outranks_a_fresh_nodes_bootstrap_admin_after_stamping() {
        AdminRecordMerge.apply(List.of(user("legacyadmin", clock.next())), true);
        cache.getPkIndexAdminUserEntry("legacyadmin").setVersion(0L);
        cache.getPkIndexAdminDbEntry(TestGlobals.DB).setVersion(0L);

        UnversionedRecords.stampAll();

        final var user = Objects.requireNonNull(AdminRecords.live(AdminRecordKey.user("legacyadmin")));
        assertTrue(user.outranks(user("legacyadmin", AdminStamp.BOOTSTRAP_VERSION)));
        assertEquals("hash-legacyadmin", cache.getAdminUserEntry("legacyadmin").getPasswordHash());
        assertTrue(liveVersion(AdminRecordKey.database(TestGlobals.DB)) > 0);
    }
}
