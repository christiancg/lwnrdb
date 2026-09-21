package org.techhouse.ejson.validate;

import java.util.HashSet;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public class JsonSchema {
    private final JsonBaseElement root;
    private final Set<RefExpansion> expanding = new HashSet<>();

    public JsonSchema(JsonBaseElement root) {
        this.root = root;
    }

    JsonBaseElement getRoot() {
        return root;
    }

    boolean beginRefExpansion(JsonBaseElement instance, JsonBaseElement target) {
        return expanding.add(new RefExpansion(instance, target));
    }

    void endRefExpansion(JsonBaseElement instance, JsonBaseElement target) {
        expanding.remove(new RefExpansion(instance, target));
    }

    private record RefExpansion(JsonBaseElement instance, JsonBaseElement target) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RefExpansion(JsonBaseElement otherInstance, JsonBaseElement otherTarget)
                    && otherInstance == instance && otherTarget == target;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(instance) * 31 + System.identityHashCode(target);
        }
    }

    JsonBaseElement resolveRef(String ref) {
        if (ref == null || ref.isEmpty() || ref.charAt(0) != '#') {
            throw new InvalidSchemaException("Unsupported $ref (only local '#/...' references are supported): " + ref);
        }
        if (ref.length() == 1) {
            return root;
        }
        if (ref.charAt(1) != '/') {
            throw new InvalidSchemaException("Unsupported $ref (expected '#/...'): " + ref);
        }
        var current = root;
        final var segments = ref.substring(2).split("/", -1);
        for (final var rawSegment : segments) {
            final var segment = decodePointerSegment(rawSegment);
            current = step(current, segment, ref);
        }
        return current;
    }

    private static JsonBaseElement step(JsonBaseElement current, String segment, String ref) {
        if (current != null && current.isJsonObject()) {
            final JsonObject obj = current.asJsonObject();
            if (!obj.has(segment)) {
                throw new InvalidSchemaException("Unresolvable $ref: " + ref);
            }
            return obj.get(segment);
        }
        if (current != null && current.isJsonArray()) {
            try {
                return current.asJsonArray().get(Integer.parseInt(segment));
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                throw new InvalidSchemaException("Unresolvable $ref: " + ref);
            }
        }
        throw new InvalidSchemaException("Unresolvable $ref: " + ref);
    }

    private static String decodePointerSegment(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }
}
