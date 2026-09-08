package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.simplejs.host.ProcedureModuleResolver;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ProcedureModuleResolverTest {
    private static final String ACTOR = "alice";
    private static final String SOURCE = "export function double(n) { return n * 2; }";
    private static final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
    }

    private static void save(String name, String source, boolean enabled) throws Exception {
        final var request = new SaveProcedureRequest(TestGlobals.DB, name, source);
        request.setEnabled(enabled);
        ProcedureOperationHelper.executeSave(request, ACTOR);
    }

    private static ProcedureModuleResolver resolver() {
        return new ProcedureModuleResolver(TestGlobals.DB);
    }

    @Test
    void resolvesStoredProcedureByName() throws Exception {
        save("lib", SOURCE, true);
        final var resolved = resolver().resolve("procedures/lib", "main");
        assertNotNull(resolved);
        assertEquals(SOURCE, resolved.source());
        assertTrue(resolved.moduleId().contains(TestGlobals.DB), resolved.moduleId());
        assertTrue(resolved.moduleId().contains("lib"), resolved.moduleId());
        assertTrue(resolved.moduleId().endsWith("|1"), resolved.moduleId());
    }

    @Test
    void moduleIdChangesWithVersion() throws Exception {
        save("lib", SOURCE, true);
        final var first = Objects.requireNonNull(resolver().resolve("procedures/lib", "main")).moduleId();
        save("lib", "export function double(n) { return n + n; }", true);
        final var second = Objects.requireNonNull(resolver().resolve("procedures/lib", "main")).moduleId();
        assertNotEquals(first, second);
    }

    @Test
    void returnsNullForUnknownPrefix() throws Exception {
        save("lib", SOURCE, true);
        final var resolver = resolver();
        assertNull(resolver.resolve("lodash", "main"));
        assertNull(resolver.resolve("./lib", "main"));
        assertNull(resolver.resolve("args", "main"));
        assertNull(resolver.resolve("db", "main"));
        assertNull(resolver.resolve("script", "main"));
        assertNull(resolver.resolve("", "main"));
        assertNull(resolver.resolve(null, "main"));
        assertNull(resolver.resolve("procedure/lib", "main"));
    }

    @Test
    void returnsNullForNestedName() throws Exception {
        save("lib", SOURCE, true);
        final var resolver = resolver();
        assertNull(resolver.resolve("procedures/a/b", "main"));
        assertNull(resolver.resolve("procedures//lib", "main"));
        assertNull(resolver.resolve("procedures/../lib", "main"));
        assertNull(resolver.resolve("procedures/", "main"));
    }

    @Test
    void returnsNullForMissingProcedure() {
        assertNull(resolver().resolve("procedures/nope", "main"));
    }

    @Test
    void returnsNullForDisabledProcedure() throws Exception {
        save("lib", SOURCE, false);
        assertNull(resolver().resolve("procedures/lib", "main"));
    }

    @Test
    void returnsNullWithoutScope() throws Exception {
        save("lib", SOURCE, true);
        assertNull(new ProcedureModuleResolver(null).resolve("procedures/lib", "main"));
    }

    @Test
    void returnsNullForAnotherDatabase() throws Exception {
        save("lib", SOURCE, true);
        assertNull(new ProcedureModuleResolver("otherDb").resolve("procedures/lib", "main"));
    }

    @Test
    void carriesTheCompiledProgramForReuse() throws Exception {
        save("lib", SOURCE, true);
        final var first = resolver().resolve("procedures/lib", "main");
        final var second = resolver().resolve("procedures/lib", "main");
        assert first != null;
        assertNotNull(first.compiled(), "the resolved module should carry a parsed program");
        assert second != null;
        assertSame(first.compiled(), second.compiled(), "two resolutions of the same version must share one parse");
        assertEquals(SOURCE, first.compiled().source());
        assertFalse(first.compiled().strictScriptGoal());
    }

    @Test
    void reparsesAfterTheProcedureChanges() throws Exception {
        save("lib", SOURCE, true);
        final var before = Objects.requireNonNull(resolver().resolve("procedures/lib", "main")).compiled();
        save("lib", "export function double(n) { return n + n; }", true);
        final var after = Objects.requireNonNull(resolver().resolve("procedures/lib", "main")).compiled();
        assertNotSame(before, after, "a save bumps the version, so the parse must not be reused");
        assertEquals("export function double(n) { return n + n; }", after.source());
    }

    @Test
    void resolvesWithoutACompiledFormWhenCachingIsDisabled() throws Exception {
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 0);
        try {
            save("lib", SOURCE, true);
            final var resolved = resolver().resolve("procedures/lib", "main");
            assert resolved != null;
            assertNotNull(resolved.compiled(), "a disabled cache still compiles, it just does not retain");
            assertEquals(SOURCE, resolved.source());
        } finally {
            TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        }
    }

    @Test
    void resolvesNameAtTheLengthBoundary() throws Exception {
        final var name = "l".repeat(64);
        save(name, SOURCE, true);
        final var resolved = resolver().resolve("procedures/" + name, "main");
        assertNotNull(resolved);
        assertEquals(SOURCE, resolved.source());
    }
}
