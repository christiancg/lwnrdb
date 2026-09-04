package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigReader;
import org.techhouse.config.Configuration;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ProcedureModuleResolver;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DatabaseHostBindingsModuleResolverTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", true);
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", true);
    }

    private static DatabaseHostBindings hostFor(String scopedDatabase) {
        final var database = new EnforcingDatabaseAccess("alice", null, scopedDatabase);
        return DatabaseHostBindings.of(null, database, null, DatabaseHostBindings.limitsFromConfiguration(), null);
    }

    @Test
    void resolverPresentWhenEnabledAndScoped() {
        assertInstanceOf(ProcedureModuleResolver.class, hostFor(TestGlobals.DB).moduleResolver());
    }

    @Test
    void resolverAbsentWhenKeyDisabled() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptProcedureImportEnabled", false);
        assertNull(hostFor(TestGlobals.DB).moduleResolver());
    }

    @Test
    void resolverAbsentWhenUnscoped() {
        assertNull(hostFor(null).moduleResolver());
    }

    @Test
    void resolverAbsentWithoutDatabaseAccess() {
        final var host = DatabaseHostBindings.of(null, null, null, DatabaseHostBindings.limitsFromConfiguration(),
                null);
        assertNull(host.moduleResolver());
    }

    @Test
    void shippedDefaultEnablesImports() {
        final var configs = ConfigReader.loadConfiguration();
        assertEquals("true", configs.get("scriptProcedureImportEnabled"),
                "the bundled default.cfg is the only source of the enabled-by-default value");
    }
}
