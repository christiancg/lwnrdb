package org.techhouse.utils;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;

public final class FieldPath {
    private static final String[] NO_SEGMENTS = new String[0];

    private final String text;
    private final String[] segments;
    private final boolean primaryKey;

    private FieldPath(String text, String[] segments) {
        this.text = text;
        this.segments = segments;
        this.primaryKey = JsonUtils.isPrimaryKeyPath(text);
    }

    public static FieldPath of(String path) {
        if (path.indexOf('.') < 0) {
            return new FieldPath(path, new String[]{path});
        }
        final var stripped = JsonUtils.stripTrailingDots(path);
        return new FieldPath(path, stripped.isEmpty() ? NO_SEGMENTS : stripped.split("\\.", -1));
    }

    public String text() {
        return text;
    }

    public List<String> segments() {
        return List.of(segments);
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public FieldPath reusedFor(String path) {
        return text.equals(path) ? this : of(path);
    }

    public JsonBaseElement resolve(JsonObject obj) {
        if (segments.length == 0) {
            return JsonNull.INSTANCE;
        }
        var current = obj.get(segments[0]);
        for (var i = 1; i < segments.length && current != null; i++) {
            if (!current.isJsonObject()) {
                return null;
            }
            current = current.asJsonObject().get(segments[i]);
        }
        return current;
    }

    public JsonBaseElement valueIn(JsonObject obj) {
        final var resolved = resolve(obj);
        return resolved == null ? JsonNull.INSTANCE : resolved;
    }

    public boolean isIn(JsonObject obj) {
        return resolve(obj) != null;
    }

    public void set(JsonObject obj, JsonBaseElement value) {
        if (segments.length == 0) {
            return;
        }
        var parent = obj;
        final var last = segments.length - 1;
        for (var i = 0; i < last; i++) {
            final var child = parent.get(segments[i]);
            if (child != null && child.isJsonObject()) {
                parent = child.asJsonObject();
            } else {
                final var created = new JsonObject();
                parent.add(segments[i], created);
                parent = created;
            }
        }
        parent.add(segments[last], value);
    }

    public void remove(JsonObject obj) {
        if (segments.length == 0) {
            return;
        }
        var parent = obj;
        final var last = segments.length - 1;
        for (var i = 0; i < last; i++) {
            final var child = parent.get(segments[i]);
            if (child == null || !child.isJsonObject()) {
                return;
            }
            parent = child.asJsonObject();
        }
        parent.remove(segments[last]);
    }
}
