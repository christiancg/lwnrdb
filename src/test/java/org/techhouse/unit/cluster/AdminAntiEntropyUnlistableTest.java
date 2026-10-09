package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyUnlistableTest {
    private static final String PROCEDURE = "proc";
    private static final String SCHEDULE = "nightly";

    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
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
    void writeDefinitions() throws Exception {
        restoreFolder(Globals.PROCEDURES_FOLDER);
        restoreFolder(Globals.SCHEDULES_FOLDER);
        writeProcedure();
        writeSchedule();
    }

    @AfterEach
    void restoreFolders() throws Exception {
        restoreFolder(Globals.PROCEDURES_FOLDER);
        restoreFolder(Globals.SCHEDULES_FOLDER);
    }

    @Test
    public void test_an_unlistable_procedures_folder_is_marked_unreadable_not_empty() throws Exception {
        makeUnlistable(Globals.PROCEDURES_FOLDER);

        final var snapshot = service.buildSnapshot();

        assertTrue(snapshot.getUnreadable().contains(folderKey("procedures", Globals.PROCEDURES_FOLDER)));
        assertTrue(snapshot.getProcedures().entrySet().isEmpty());
    }

    @Test
    public void test_an_unlistable_schedules_folder_is_marked_unreadable_not_empty() throws Exception {
        makeUnlistable(Globals.SCHEDULES_FOLDER);

        final var snapshot = service.buildSnapshot();

        assertTrue(snapshot.getUnreadable().contains(folderKey("schedules", Globals.SCHEDULES_FOLDER)));
        assertTrue(snapshot.getSchedules().entrySet().isEmpty());
    }

    @Test
    public void test_a_snapshot_whose_folders_could_not_be_listed_deletes_nothing_locally() throws Exception {
        makeUnlistable(Globals.PROCEDURES_FOLDER);
        makeUnlistable(Globals.SCHEDULES_FOLDER);
        final var snapshot = service.buildSnapshot();
        writeDefinitions();

        assertFalse(conform(snapshot), "a conform that skipped a folder is not complete");

        assertTrue(fs.listProcedureNames(TestGlobals.DB).contains(PROCEDURE));
        assertTrue(fs.listScheduleNames(TestGlobals.DB).contains(SCHEDULE));
    }

    @Test
    public void test_a_local_folder_that_cannot_be_listed_leaves_the_conform_incomplete() throws Exception {
        final var snapshot = service.buildSnapshot();
        snapshot.getProcedures().remove(Cache.getCollectionIdentifier(TestGlobals.DB, PROCEDURE));
        makeUnlistable(Globals.PROCEDURES_FOLDER);

        assertFalse(assertDoesNotThrow(() -> conform(snapshot)));
    }

    @Test
    public void test_a_local_delete_that_fails_leaves_the_conform_incomplete() throws Exception {
        final var snapshot = service.buildSnapshot();
        final var procedures = folder(Globals.PROCEDURES_FOLDER);
        snapshot.getProcedures().remove(Cache.getCollectionIdentifier(TestGlobals.DB, PROCEDURE));
        Files.setPosixFilePermissions(procedures.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        assumeFalse(Files.isWritable(procedures.toPath()), "a superuser ignores the permission bits");

        assertFalse(conform(snapshot));

        Files.setPosixFilePermissions(procedures.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(fs.listProcedureNames(TestGlobals.DB).contains(PROCEDURE));
    }

    private File folder(String name) throws Exception {
        return new File(TestUtils.getDbPath(fs), TestGlobals.DB + File.separator + name);
    }

    private void makeUnlistable(String name) throws Exception {
        final var folder = folder(name);
        TestUtils.deleteFolder(folder);
        Files.writeString(folder.toPath(), "not a folder");
    }

    private void restoreFolder(String name) throws Exception {
        final var folder = folder(name);
        if (folder.isFile()) {
            Files.delete(folder.toPath());
        }
    }

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        final var conformerField = AdminAntiEntropyService.class.getDeclaredField("conformer");
        conformerField.setAccessible(true);
        final var conformer = conformerField.get(service);
        final var method = conformer.getClass().getDeclaredMethod("conform", AdminSnapshotPayload.class, long.class);
        method.setAccessible(true);
        return (boolean) method.invoke(conformer, snapshot, IocContainer.get(AdminEpoch.class).current());
    }

    private static String folderKey(String kind, String folderName) {
        return kind + "|" + Cache.getCollectionIdentifier(TestGlobals.DB, folderName);
    }

    private void writeProcedure() throws Exception {
        final var definition = new ProcedureDefinition(PROCEDURE, "return 1;", 1L, null, true, 1L, 1L, "alice");
        fs.writeProcedure(TestGlobals.DB, PROCEDURE, eJson.toJson(definition.toJsonObject()));
        cache.removeProcedure(TestGlobals.DB, PROCEDURE);
    }

    private void writeSchedule() throws Exception {
        final var definition = new ScheduleDefinition(SCHEDULE, PROCEDURE, null, 60_000L, null, 0L, true, "alice", null,
                1L, 1L, 1L, "alice");
        fs.writeSchedule(TestGlobals.DB, SCHEDULE, eJson.toJson(definition.toJsonObject()));
        cache.removeSchedule(TestGlobals.DB, SCHEDULE);
    }
}
