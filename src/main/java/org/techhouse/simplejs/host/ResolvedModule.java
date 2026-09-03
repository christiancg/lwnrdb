package org.techhouse.simplejs.host;

import org.techhouse.simplejs.CompiledScript;

/**
 * A module the host claimed. {@code compiled} is optional: when present, and parsed under the same goal the
 * run uses, the importer reuses that program instead of re-lexing and re-parsing the source on every run.
 * The parse goal decides which early errors are raised, so a program parsed under the other one is the wrong
 * program - which is why the caller checks rather than assumes.
 */
public record ResolvedModule(String moduleId, String source, CompiledScript compiled) {
    public ResolvedModule(String moduleId, String source) {
        this(moduleId, source, null);
    }
}
