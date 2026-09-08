package org.techhouse.simplejs.host;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledProcedureCache;

public final class ProcedureModuleResolver implements ModuleResolver {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    public static final String SPECIFIER_PREFIX = "procedures/";

    private final String scopedDatabase;

    public ProcedureModuleResolver(String scopedDatabase) {
        this.scopedDatabase = scopedDatabase;
    }

    @Override
    public ResolvedModule resolve(String specifier, String referrer) {
        if (scopedDatabase == null || specifier == null || !specifier.startsWith(SPECIFIER_PREFIX)) {
            return null;
        }
        final var name = specifier.substring(SPECIFIER_PREFIX.length());
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
                compiledProcedures.get(scopedDatabase, name, version, source), SPECIFIER_PREFIX + name);
    }

    private String moduleId(String name, long version) {
        return "procedure:" + scopedDatabase + Globals.COLL_IDENTIFIER_SEPARATOR + name
                + Globals.COLL_IDENTIFIER_SEPARATOR + version;
    }
}
