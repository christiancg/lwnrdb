package org.techhouse.ejson.validate;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public class JsonSchema {
    private final JsonBaseElement root;

    public JsonSchema(JsonBaseElement root) {
        this.root = root;
    }

    JsonBaseElement getRoot() {
        return root;
    }

    // Resolves a local JSON-Pointer $ref (e.g. "#/$defs/name") against the schema document root.
    // Only same-document fragment references are supported; anything else is an invalid schema here.
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
