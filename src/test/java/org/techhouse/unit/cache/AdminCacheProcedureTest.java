package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminCacheProcedureTest {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() {
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
    }

    private ProcedureDefinition write(String name) throws Exception {
        final var definition = new ProcedureDefinition(name, "return " + 1L + ";", 1L, null, true, 1L, 1L, "alice");
        fs.writeProcedure(TestGlobals.DB, name, eJson.toJson(definition.toJsonObject()));
        return definition;
    }

    // Nothing is loaded at startup, so a first access reads the file
    @Test
    public void test_get_procedure_loads_lazily_from_disk() throws Exception {
        final var written = write("lazy");
        assertEquals(written, cache.getProcedure(TestGlobals.DB, "lazy"));
    }

    // Absence is negatively cached, so a misspelled name in a loop reads the disk once. Proven by
    // writing the file after the miss: the cache must still answer null.
    @Test
    public void test_absence_is_negatively_cached() throws Exception {
        assertNull(cache.getProcedure(TestGlobals.DB, "later"));
        write("later");
        assertNull(cache.getProcedure(TestGlobals.DB, "later"));
    }

    @Test
    public void test_put_procedure_overrides_the_cached_value() throws Exception {
        write("p");
        assertEquals(1L, cache.getProcedure(TestGlobals.DB, "p").getVersion());
        final var bumped = new ProcedureDefinition("p", "return 2;", 2L, null, true, 1L, 2L, "bob");
        cache.putProcedure(TestGlobals.DB, bumped);
        assertEquals(2L, cache.getProcedure(TestGlobals.DB, "p").getVersion());
    }

    @Test
    public void test_remove_procedure_forgets_only_that_name() throws Exception {
        write("keep");
        write("drop");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "keep"));
        assertNotNull(cache.getProcedure(TestGlobals.DB, "drop"));
        fs.deleteProcedure(TestGlobals.DB, "drop");
        cache.removeProcedure(TestGlobals.DB, "drop");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "keep"));
        assertNull(cache.getProcedure(TestGlobals.DB, "drop"));
    }

    @Test
    public void test_remove_procedures_for_database_leaves_other_databases() throws Exception {
        write("p");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p"));
        cache.removeProceduresForDatabase("someOtherDb");
        assertNotNull(cache.getProcedure(TestGlobals.DB, "p"));
        cache.removeProceduresForDatabase(TestGlobals.DB);
        fs.deleteProcedure(TestGlobals.DB, "p");
        assertNull(cache.getProcedure(TestGlobals.DB, "p"));
    }

    // A malformed file must not fail every later read of that name
    @Test
    public void test_malformed_procedure_file_reads_as_absent() throws Exception {
        fs.writeProcedure(TestGlobals.DB, "broken", "not json at all");
        assertNull(cache.getProcedure(TestGlobals.DB, "broken"));
    }
}
