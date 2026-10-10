package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DropCollectionRequest;
import org.techhouse.ops.req.DropDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DropUnregisteredNameTest {
    private static final String STRAY_COLL = "strayCollection";
    private static final String STRAY_DB = "strayDatabase";

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        final var strayDatabase = databaseFolder(STRAY_DB);
        if (strayDatabase.isDirectory()) {
            TestUtils.deleteFolder(strayDatabase);
        }
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static File collectionFolder(String dbName, String collName) {
        return new File(new File(TestGlobals.PATH, dbName), collName);
    }

    private static File databaseFolder(String dbName) {
        return new File(TestGlobals.PATH, dbName);
    }

    private static File createStrayFolder(File folder) {
        assertTrue(folder.mkdirs(), "the fixture could not create " + folder);
        return folder;
    }

    private OperationStatus dropCollection(String dbName, String collName) {
        return processor.processMessage(new DropCollectionRequest(dbName, collName)).getStatus();
    }

    private OperationStatus dropDatabase(String dbName) {
        return processor.processMessage(new DropDatabaseRequest(dbName)).getStatus();
    }

    @Test
    public void shouldLeaveAnUnregisteredCollectionFolderOnDisk() {
        final var stray = createStrayFolder(collectionFolder(TestGlobals.DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldLeaveAnUnregisteredDatabaseFolderOnDisk() {
        final var stray = createStrayFolder(databaseFolder(STRAY_DB));
        assertEquals(OperationStatus.ERROR, dropDatabase(STRAY_DB));
        assertTrue(stray.isDirectory(), "an unregistered database folder must not be deleted");
    }

    @Test
    public void shouldLeaveACollectionFolderOnDiskWhenItsDatabaseIsNotRegistered() {
        final var stray = createStrayFolder(collectionFolder(STRAY_DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(STRAY_DB, STRAY_COLL));
        assertTrue(stray.isDirectory(), "an unregistered collection folder must not be deleted");
    }

    @Test
    public void shouldStillDropARegisteredCollection() {
        final var registered = collectionFolder(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(registered.exists(), "a registered collection must still be removed");
    }

    @Test
    public void shouldStillDropARegisteredDatabase() {
        final var registered = databaseFolder(TestGlobals.DB);
        assertTrue(registered.isDirectory());
        assertEquals(OperationStatus.OK, dropDatabase(TestGlobals.DB));
        assertFalse(registered.exists(), "a registered database must still be removed");
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameCollection() {
        assertEquals(OperationStatus.OK, dropCollection(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void shouldRefuseASecondDropOfTheSameDatabase() {
        assertEquals(OperationStatus.OK, dropDatabase(TestGlobals.DB));
        assertEquals(OperationStatus.ERROR, dropDatabase(TestGlobals.DB));
    }

    @Test
    public void shouldLeaveTheRegisteredSiblingIntactWhenAStrayFolderIsDropped() {
        createStrayFolder(collectionFolder(TestGlobals.DB, STRAY_COLL));
        assertEquals(OperationStatus.ERROR, dropCollection(TestGlobals.DB, STRAY_COLL));
        assertTrue(collectionFolder(TestGlobals.DB, TestGlobals.COLL).isDirectory(),
                "dropping an unregistered name must not touch a registered collection");
    }
}
