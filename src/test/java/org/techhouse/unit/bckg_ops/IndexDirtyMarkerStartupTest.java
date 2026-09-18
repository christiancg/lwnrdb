package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.StartupWarnings;
import org.techhouse.cache.Cache;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexDirtyMarkerStartupTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        fs.clearIndexesDirty(TestGlobals.DB, TestGlobals.COLL);
        TestUtils.standardTearDown();
    }

    private static String identifier() {
        return Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_startup_warns_for_a_collection_left_marked() {
        fs.markIndexesDirty(TestGlobals.DB, TestGlobals.COLL);

        assertTrue(fs.listDirtyIndexCollections().contains(identifier()),
                "an unclean stop leaves the marker behind, and startup is where an operator can act on it");
        assertDoesNotThrow(StartupWarnings::warnIfIndexesLeftDirty);
    }

    @Test
    public void test_a_drained_collection_is_not_reported_at_startup() {
        fs.markIndexesDirty(TestGlobals.DB, TestGlobals.COLL);
        fs.clearIndexesDirty(TestGlobals.DB, TestGlobals.COLL);

        assertFalse(fs.listDirtyIndexCollections().contains(identifier()));
        assertDoesNotThrow(StartupWarnings::warnIfIndexesLeftDirty);
    }
}
