package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminResidentRows;
import org.techhouse.test.TestUtils;

public class AdminResidentRowsTest {
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static PkIndexEntry row(String collName, String id) {
        return new PkIndexEntry(Globals.ADMIN_DB_NAME, collName, id, 0, 10, 0);
    }

    private static List<PkIndexEntry> residentRows(String collName) {
        final var rows = AdminResidentRows.of(collName);
        assertNotNull(rows);
        return rows;
    }

    @Test
    public void test_the_rows_are_copied_on_the_first_read_not_when_asked_for() {
        final var rows = residentRows(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME);
        cache.putPkIndexTriggerRun(row(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "run|0"));

        assertEquals(1, rows.size());
        cache.putPkIndexTriggerRun(row(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, "run|1"));

        assertEquals(1, rows.size(), "the first read fixes the copy");
        assertEquals("run|0", rows.getFirst().getValue());
    }

    @Test
    public void test_every_resident_admin_collection_has_a_view_and_others_have_none() {
        cache.putPkIndexCollectionUsage(row(Globals.ADMIN_COLLECTION_USAGE_NAME, "db|coll|"));

        assertEquals(1, residentRows(Globals.ADMIN_COLLECTION_USAGE_NAME).size());
        assertEquals(0, residentRows(Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME).size());
        assertNull(AdminResidentRows.of(Globals.ADMIN_USERS_COLLECTION_NAME));
        assertNull(AdminResidentRows.of(null));
    }
}
