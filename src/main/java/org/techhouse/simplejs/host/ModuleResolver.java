package org.techhouse.simplejs.host;

public interface ModuleResolver {
    ResolvedModule resolve(String specifier, String referrer);
}
