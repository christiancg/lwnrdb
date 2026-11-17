package org.techhouse.utils;

import java.util.HashMap;
import java.util.Map;

public final class FieldPathMemo {
    private final Map<String, FieldPath> paths = new HashMap<>();

    public FieldPath of(String path) {
        return paths.computeIfAbsent(path, FieldPath::of);
    }
}
