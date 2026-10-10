package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.Main;
import org.techhouse.cluster.HybridClock;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MainClockSeedTest {
    private static final String UNREADABLE_COLL = "unreadablecoll";
    private final Configuration config = Configuration.getInstance();
    private final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private boolean origEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, UNREADABLE_COLL));
        IocContainer.get(FileSystem.class).createCollectionFile(TestGlobals.DB, UNREADABLE_COLL);
        origEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(hybridClock, "last", new AtomicLong(0));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(hybridClock, "last", new AtomicLong(0));
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File pkIndexFile(String collName) {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + collName
                + Globals.FILE_SEPARATOR + collName + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.PK_INDEX_FILE_NAME
                + Globals.INDEX_FILE_EXTENSION);
    }

    @Test
    public void test_one_unreadable_pk_index_does_not_leave_the_whole_clock_unseeded() throws Exception {
        final var planted = HybridClock.pack(System.currentTimeMillis() + 864_000_000L, 5);
        Files.writeString(pkIndexFile(TestGlobals.COLL).toPath(),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "a", 0, 10, 0, planted).toFileEntry() + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(pkIndexFile(UNREADABLE_COLL).toPath(), "a|0|20|0|0\n", StandardCharsets.UTF_8);
        final var seed = Main.class.getDeclaredMethod("seedHybridClock");
        seed.setAccessible(true);

        seed.invoke(null);

        assertEquals(planted, hybridClock.current());
    }

    private void seed() throws Exception {
        final var seed = Main.class.getDeclaredMethod("seedHybridClock");
        seed.setAccessible(true);
        seed.invoke(null);
    }

    @Test
    public void test_the_clock_is_seeded_when_clustering_is_off() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        final var planted = HybridClock.pack(System.currentTimeMillis() + 864_000_000L, 3);
        Files.writeString(pkIndexFile(TestGlobals.COLL).toPath(),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "a", 0, 10, 0, planted).toFileEntry() + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(pkIndexFile(UNREADABLE_COLL).toPath(), "", StandardCharsets.UTF_8);

        seed();

        assertEquals(planted, hybridClock.current(),
                "a standalone replay compares versions too, so a clock behind the stored ones drops committed writes");
    }

    @Test
    public void test_buffered_op_versions_are_part_of_the_seed() throws Exception {
        Files.writeString(pkIndexFile(UNREADABLE_COLL).toPath(), "", StandardCharsets.UTF_8);
        final var planted = HybridClock.pack(System.currentTimeMillis() + 864_000_000L, 9);
        final var op = new AdminTransactionEntry("seed-tx", "client", 0, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, TestGlobals.COLL, new JsonObject());
        op.setVersions(List.of(planted));
        AdminOperationHelper.saveTransactionOp(op);
        try {
            seed();

            assertEquals(planted, hybridClock.current());
        } finally {
            AdminOperationHelper.deleteTransactionOps(List.of(op.get_id()));
        }
    }
}
