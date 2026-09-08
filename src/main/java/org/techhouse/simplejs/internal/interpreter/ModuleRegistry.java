package org.techhouse.simplejs.internal.interpreter;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.simplejs.values.JsValue;

public final class ModuleRegistry {
    public enum State {
        EVALUATING, EVALUATED, FAILED
    }

    private record Entry(State state, JsValue namespace, RuntimeException failure) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public State stateOf(String moduleId) {
        final var entry = entries.get(moduleId);
        return entry == null ? null : entry.state();
    }

    public JsValue namespaceOf(String moduleId) {
        return entries.get(moduleId).namespace();
    }

    public RuntimeException failureOf(String moduleId) {
        return entries.get(moduleId).failure();
    }

    public void beginEvaluation(String moduleId) {
        entries.put(moduleId, new Entry(State.EVALUATING, null, null));
    }

    public void complete(String moduleId, JsValue namespace) {
        entries.put(moduleId, new Entry(State.EVALUATED, namespace, null));
    }

    public void fail(String moduleId, RuntimeException error) {
        entries.put(moduleId, new Entry(State.FAILED, null, error));
    }
}
