package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.StartupWarnings;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class StartupWarningsNameCollisionTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void createCollection(String collName) {
        final var request = new CreateCollectionRequest(TestGlobals.DB, collName);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void createDatabase(String dbName) {
        assertEquals(OperationStatus.OK, processor.processMessage(new CreateDatabaseRequest(dbName)).getStatus());
    }

    private static void plantSharedNameCollection() throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, "SharedName"));
    }

    private static void plantDatabase(String dbName) throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(dbName));
    }

    private static void assertLogged(MockedStatic<LogWriter> logWriter, String expected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))));
    }

    @Test
    public void test_two_collections_in_one_database_sharing_a_key_warn() throws Exception {
        createCollection("sharedName");
        plantSharedNameCollection();
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            assertLogged(logWriter, "share one on-disk path");
            assertLogged(logWriter, "sharedName, SharedName");
        }
    }

    @Test
    public void test_two_databases_sharing_an_on_disk_key_warn() throws Exception {
        createDatabase("caseProbe");
        plantDatabase("CaseProbe");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            assertLogged(logWriter, "caseProbe, CaseProbe");
        }
    }

    @Test
    public void test_same_name_in_different_databases_does_not_warn() {
        createDatabase("otherDb");
        createCollection("sharedName");
        final var request = new CreateCollectionRequest("otherDb", "sharedName");
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_a_database_named_like_the_cluster_folder_is_warned_about() throws Exception {
        plantDatabase("cluster");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDatabaseSharesTheClusterFolder();
            assertLogged(logWriter, "Database 'cluster' is stored in the folder this node keeps its cluster state");
        }
    }

    @Test
    public void test_an_ordinary_database_is_not_warned_about_the_cluster_folder() {
        createDatabase("clusters");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDatabaseSharesTheClusterFolder();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_distinct_names_are_silent() {
        createCollection("alpha");
        createCollection("beta");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfNamesShareAnOnDiskKey();
            logWriter.verifyNoInteractions();
        }
    }
}
