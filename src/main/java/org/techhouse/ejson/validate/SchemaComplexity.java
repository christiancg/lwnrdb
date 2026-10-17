package org.techhouse.ejson.validate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

public final class SchemaComplexity {
    private final JsonSchema schema;
    private final long ceiling;
    private final Map<JsonBaseElement, Long> counted = new IdentityHashMap<>();
    private final Set<JsonBaseElement> expanding = Collections.newSetFromMap(new IdentityHashMap<>());

    private SchemaComplexity(JsonBaseElement root, int limit) {
        this.schema = new JsonSchema(root);
        this.ceiling = (long) limit + 1;
    }

    public static long expandedNodeCount(JsonBaseElement root, int limit) {
        return new SchemaComplexity(root, limit).count(root);
    }

    private long count(JsonBaseElement node) {
        if (!node.isJsonObject()) {
            return 1;
        }
        final var memoised = counted.get(node);
        if (memoised != null) {
            return memoised;
        }
        if (!expanding.add(node)) {
            return 0;
        }
        try {
            final var total = saturated(1 + childrenCount(node) + refCount(node));
            counted.put(node, total);
            return total;
        } finally {
            expanding.remove(node);
        }
    }

    private long childrenCount(JsonBaseElement node) {
        long total = 0;
        for (final var entry : node.asJsonObject().entrySet()) {
            total = saturated(total + subschemaCount(entry.getKey(), entry.getValue()));
        }
        return total;
    }

    private long subschemaCount(String keyword, JsonBaseElement value) {
        if (SchemaRefValidator.SINGLE_SCHEMA_KEYWORDS.contains(keyword)) {
            return count(value);
        }
        long total = 0;
        if (SchemaRefValidator.SCHEMA_ARRAY_KEYWORDS.contains(keyword) && value.isJsonArray()) {
            for (final var item : value.asJsonArray()) {
                total = saturated(total + count(item));
            }
        } else if (SchemaRefValidator.SCHEMA_MAP_KEYWORDS.contains(keyword) && !SchemaKeywords.DEFS.equals(keyword)
                && value.isJsonObject()) {
            for (final var entry : value.asJsonObject().entrySet()) {
                total = saturated(total + count(entry.getValue()));
            }
        }
        return total;
    }

    private long refCount(JsonBaseElement node) {
        final var ref = node.asJsonObject().get(SchemaKeywords.REF);
        if (ref == null || !ref.isJsonString()) {
            return 0;
        }
        try {
            return count(schema.resolveRef(ref.asJsonString().getValue()));
        } catch (InvalidSchemaException e) {
            return 0;
        }
    }

    private long saturated(long value) {
        return Math.min(value, ceiling);
    }
}
