package org.techhouse.ejson.validate;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public final class SchemaPatternLengths {
    private final JsonSchema schema;
    private final Set<JsonBaseElement> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<JsonBaseElement> pending = new ArrayDeque<>();
    private int longest;

    private SchemaPatternLengths(JsonBaseElement root) {
        this.schema = new JsonSchema(root);
    }

    public static int longestPattern(JsonBaseElement root) {
        final var lengths = new SchemaPatternLengths(root);
        lengths.pending.push(root);
        while (!lengths.pending.isEmpty()) {
            lengths.visit(lengths.pending.pop());
        }
        return lengths.longest;
    }

    private void visit(JsonBaseElement node) {
        if (!node.isJsonObject() || !visited.add(node)) {
            return;
        }
        final var object = node.asJsonObject();
        notePattern(object.get(SchemaKeywords.PATTERN));
        final var patternProperties = object.get(SchemaKeywords.PATTERN_PROPERTIES);
        if (patternProperties != null && patternProperties.isJsonObject()) {
            patternProperties.asJsonObject().entrySet()
                    .forEach(entry -> longest = Math.max(longest, entry.getKey().length()));
        }
        for (final var entry : object.entrySet()) {
            queueSubschemas(entry.getKey(), entry.getValue());
        }
        queueRefTarget(object);
    }

    private void notePattern(JsonBaseElement pattern) {
        if (pattern != null && pattern.isJsonString()) {
            longest = Math.max(longest, pattern.asJsonString().getValue().length());
        }
    }

    private void queueSubschemas(String keyword, JsonBaseElement value) {
        if (SchemaRefValidator.SINGLE_SCHEMA_KEYWORDS.contains(keyword)) {
            pending.push(value);
        } else if (SchemaRefValidator.SCHEMA_ARRAY_KEYWORDS.contains(keyword) && value.isJsonArray()) {
            value.asJsonArray().forEach(pending::push);
        } else if (SchemaRefValidator.SCHEMA_MAP_KEYWORDS.contains(keyword) && value.isJsonObject()) {
            value.asJsonObject().entrySet().forEach(entry -> pending.push(entry.getValue()));
        }
    }

    private void queueRefTarget(JsonObject object) {
        final var target = refTarget(object.get(SchemaKeywords.REF));
        if (target != null) {
            pending.push(target);
        }
    }

    private JsonBaseElement refTarget(JsonBaseElement ref) {
        if (ref == null || !ref.isJsonString()) {
            return null;
        }
        try {
            return schema.resolveRef(ref.asJsonString().getValue());
        } catch (InvalidSchemaException e) {
            return null;
        }
    }
}
