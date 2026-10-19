package org.techhouse.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.techhouse.ejson.elements.JsonObject;

public final class ProcedureOrder {
    private static final Pattern IMPORT = Pattern.compile("[\"']procedures/([A-Za-z0-9_-]+)[\"']");
    private static final String SOURCE = "source";

    private final Map<String, JsonObject> byName = new LinkedHashMap<>();
    private final Map<String, Set<String>> imports = new LinkedHashMap<>();

    public ProcedureOrder(List<JsonObject> listed) {
        for (final var procedure : listed) {
            final var name = Connection.stringOf(procedure, Requests.NAME);
            if (name != null) {
                byName.putIfAbsent(name, procedure);
                imports.putIfAbsent(name, importsOf(Connection.stringOf(procedure, SOURCE)));
            }
        }
    }

    private static Set<String> importsOf(String source) {
        final var found = new LinkedHashSet<String>();
        if (source != null) {
            final var matcher = IMPORT.matcher(source);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    public List<JsonObject> order() {
        final var emitted = new LinkedHashSet<String>();
        while (emitted.size() < byName.size()) {
            emitted.add(nextReady(emitted));
        }
        final var ordered = new ArrayList<JsonObject>();
        for (final var name : emitted) {
            ordered.add(byName.get(name));
        }
        return ordered;
    }

    private String nextReady(Set<String> emitted) {
        String firstOnCycle = null;
        for (final var name : byName.keySet()) {
            if (emitted.contains(name)) {
                continue;
            }
            if (isReady(name, emitted)) {
                return name;
            }
            if (firstOnCycle == null && reaches(name, name, emitted)) {
                firstOnCycle = name;
            }
        }
        return firstOnCycle;
    }

    private boolean reaches(String from, String target, Set<String> emitted) {
        final var seen = new LinkedHashSet<String>();
        final var pending = new ArrayDeque<>(imports.get(from));
        while (!pending.isEmpty()) {
            final var name = pending.poll();
            if (name.equals(target)) {
                return true;
            }
            if (byName.containsKey(name) && !emitted.contains(name) && seen.add(name)) {
                pending.addAll(imports.get(name));
            }
        }
        return false;
    }

    private boolean isReady(String name, Set<String> emitted) {
        for (final var dependency : imports.get(name)) {
            if (byName.containsKey(dependency) && !emitted.contains(dependency)) {
                return false;
            }
        }
        return true;
    }

    public Set<String> closureOf(Collection<String> roots) {
        final var closure = new LinkedHashSet<String>();
        final var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            final var name = pending.poll();
            if (byName.containsKey(name) && closure.add(name)) {
                pending.addAll(imports.get(name));
            }
        }
        return closure;
    }
}
