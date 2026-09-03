package org.techhouse.simplejs.host;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;

/**
 * Resolves {@code import … from "procedures/<name>"} against the stored procedures of the one database a
 * script is scoped to, so a procedure, trigger or scheduled procedure can import shared code instead of
 * copying it. A library <em>is</em> a stored procedure: {@code SAVE_PROCEDURE}/{@code DELETE_PROCEDURE}
 * already manage it, and nothing new is persisted or replicated.
 *
 * <p>
 * The namespace is flat and scope-restricted: a name containing a {@code /} is refused (no traversal, and
 * no ambiguity about the always-{@code "main"} referrer the caller passes), and only {@code scopedDatabase}
 * is searched, which is the same boundary {@link EnforcingDatabaseAccess} enforces for data. Anything this
 * resolver does not claim returns {@code null}, which the caller turns into the standard catchable
 * {@code Cannot find module '…'} - including a disabled procedure, mirroring {@code CALL_PROCEDURE}
 * answering not-found rather than running one.
 *
 * <p>
 * The module id carries the procedure's version. The registry that consumes it is per-run, so the version
 * is not needed to keep two runs apart; it makes the id meaningful in a cycle or depth error message, and
 * it is the key {@code CompiledProcedureCache} is already keyed by - which is what lets the resolved module
 * carry the parsed program so an imported library is not re-parsed on every run of every importer. Keying
 * by version is also what makes that safe: a save bumps the version, so a stale parse can never be served.
 */
public final class ProcedureModuleResolver implements ModuleResolver {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final String PREFIX = "procedures/";

    private final String scopedDatabase;

    public ProcedureModuleResolver(String scopedDatabase) {
        this.scopedDatabase = scopedDatabase;
    }

    @Override
    public ResolvedModule resolve(String specifier, String referrer) {
        if (scopedDatabase == null || specifier == null || !specifier.startsWith(PREFIX)) {
            return null;
        }
        final var name = specifier.substring(PREFIX.length());
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            return null;
        }
        final var definition = cache.getProcedure(scopedDatabase, name);
        if (definition == null || !definition.isEnabled() || definition.getSource() == null) {
            return null;
        }
        final var version = definition.getVersion();
        final var source = definition.getSource();
        return new ResolvedModule(moduleId(name, version), source,
                compiledProcedures.get(scopedDatabase, name, version, source));
    }

    private String moduleId(String name, long version) {
        return "procedure:" + scopedDatabase + Globals.COLL_IDENTIFIER_SEPARATOR + name
                + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
