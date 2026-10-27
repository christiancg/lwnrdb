package org.techhouse.ejson.elements;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WriteTree {
    private static final WriteTree LEAF = new WriteTree(Map.of());

    private final Map<String, WriteTree> children;

    private WriteTree(Map<String, WriteTree> children) {
        this.children = children;
    }

    public static WriteTree of(Collection<List<String>> segmentedPaths) {
        final var root = new Builder();
        for (final var segments : segmentedPaths) {
            var node = root;
            for (final var segment : segments) {
                node = node.children.computeIfAbsent(segment, _ -> new Builder());
            }
        }
        return root.build();
    }

    public WriteTree child(String key) {
        return children.get(key);
    }

    public Set<String> roots() {
        return children.keySet();
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    private static final class Builder {
        private final Map<String, Builder> children = new HashMap<>();

        private WriteTree build() {
            if (children.isEmpty()) {
                return LEAF;
            }
            final var built = new HashMap<String, WriteTree>(children.size() * 2);
            children.forEach((key, child) -> built.put(key, child.build()));
            return new WriteTree(built);
        }
    }
}
