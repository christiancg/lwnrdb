package org.techhouse.simplejs.host;

public interface ModuleResolver {
    // A null return means "unknown to this resolver", which the caller turns into the standard
    // catchable "Cannot find module '…'". moduleId is the registry key, so two specifiers naming the
    // same module must resolve to the same id or the module is evaluated twice.
    ResolvedModule resolve(String specifier, String referrer);
}
