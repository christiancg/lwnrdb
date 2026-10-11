package org.techhouse.ejson.validate;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBaseElement.JsonType;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.InvalidSchemaException;

final class SchemaRefValidator {
    private static final Set<String> SINGLE_SCHEMA_KEYWORDS = Set.of(SchemaKeywords.ADDITIONAL_PROPERTIES,
            SchemaKeywords.PROPERTY_NAMES, SchemaKeywords.ITEMS, SchemaKeywords.CONTAINS, SchemaKeywords.NOT,
            SchemaKeywords.IF, SchemaKeywords.THEN, SchemaKeywords.ELSE);
    private static final Set<String> SCHEMA_ARRAY_KEYWORDS = Set.of(SchemaKeywords.PREFIX_ITEMS, SchemaKeywords.ALL_OF,
            SchemaKeywords.ANY_OF, SchemaKeywords.ONE_OF);
    private static final Set<String> SCHEMA_MAP_KEYWORDS = Set.of(SchemaKeywords.PROPERTIES,
            SchemaKeywords.PATTERN_PROPERTIES, SchemaKeywords.DEPENDENT_SCHEMAS, SchemaKeywords.DEFS);
    private static final Set<String> SAME_INSTANCE_SINGLE_KEYWORDS = Set.of(SchemaKeywords.NOT, SchemaKeywords.IF,
            SchemaKeywords.THEN, SchemaKeywords.ELSE);
    private static final Set<String> SAME_INSTANCE_ARRAY_KEYWORDS = Set.of(SchemaKeywords.ALL_OF, SchemaKeywords.ANY_OF,
            SchemaKeywords.ONE_OF);

    private final JsonSchema schema;
    private final List<String> errors;
    private final Set<JsonBaseElement> expanding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<JsonBaseElement> settled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<JsonBaseElement> walked = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<RefTarget> unwalkedTargets = new ArrayDeque<>();
    private final BiConsumer<JsonBaseElement, String> metaCheck;

    SchemaRefValidator(JsonBaseElement root, List<String> errors, BiConsumer<JsonBaseElement, String> metaCheck) {
        this.schema = new JsonSchema(root);
        this.errors = errors;
        this.metaCheck = metaCheck;
    }

    void validateAll(JsonBaseElement root) {
        walk(root, "");
        while (!unwalkedTargets.isEmpty()) {
            final var target = unwalkedTargets.removeFirst();
            if (walked.contains(target.node())) {
                continue;
            }
            metaCheck.accept(target.node(), target.path());
            walk(target.node(), target.path());
        }
    }

    private void walk(JsonBaseElement node, String path) {
        if (node == null || !node.isJsonObject() || !walked.add(node)) {
            return;
        }
        final var obj = node.asJsonObject();
        if (obj.has(SchemaKeywords.REF)) {
            checkChain(obj, path);
        }
        for (final var entry : obj.entrySet()) {
            visitSubschemas(entry.getKey(), entry.getValue(), path + "/" + entry.getKey());
        }
    }

    private void visitSubschemas(String keyword, JsonBaseElement value, String path) {
        if (SINGLE_SCHEMA_KEYWORDS.contains(keyword)) {
            walk(value, path);
        } else if (SCHEMA_ARRAY_KEYWORDS.contains(keyword) && value.isJsonArray()) {
            final var arr = value.asJsonArray();
            for (var i = 0; i < arr.size(); i++) {
                walk(arr.get(i), path + "/" + i);
            }
        } else if (SCHEMA_MAP_KEYWORDS.contains(keyword) && value.isJsonObject()) {
            for (final var entry : value.asJsonObject().entrySet()) {
                walk(entry.getValue(), path + "/" + entry.getKey());
            }
        }
    }

    private void checkChain(JsonBaseElement node, String path) {
        if (node == null || !node.isJsonObject()) {
            return;
        }
        if (expanding.contains(node)) {
            errors.add(SchemaValidator.at(path) + ": '" + SchemaKeywords.REF
                    + "' forms a reference cycle that would never terminate");
            return;
        }
        if (!settled.add(node)) {
            return;
        }
        expanding.add(node);
        try {
            followRef(node.asJsonObject(), path);
            followSameInstanceKeywords(node.asJsonObject(), path);
        } finally {
            expanding.remove(node);
        }
    }

    private void followRef(JsonObject node, String path) {
        final var ref = node.get(SchemaKeywords.REF);
        if (ref == null || !ref.isJsonString()) {
            return;
        }
        final var refPath = path + "/" + SchemaKeywords.REF;
        final JsonBaseElement resolved;
        try {
            resolved = schema.resolveRef(ref.asJsonString().getValue());
        } catch (InvalidSchemaException e) {
            errors.add(SchemaValidator.at(refPath) + ": " + e.getMessage());
            return;
        }
        if (!isSchemaNode(resolved)) {
            errors.add(SchemaValidator.at(refPath) + ": '" + SchemaKeywords.REF
                    + "' must resolve to a schema (an object or a boolean)");
            return;
        }
        if (!walked.contains(resolved)) {
            unwalkedTargets.addLast(new RefTarget(resolved, pointerPathOf(ref.asJsonString().getValue())));
        }
        checkChain(resolved, refPath);
    }

    private static String pointerPathOf(String ref) {
        return ref.substring(1);
    }

    private static boolean isSchemaNode(JsonBaseElement node) {
        return node != null && (node.isJsonObject() || node.getJsonType() == JsonType.BOOLEAN);
    }

    private void followSameInstanceKeywords(JsonObject node, String path) {
        for (final var entry : node.entrySet()) {
            final var keyword = entry.getKey();
            final var value = entry.getValue();
            final var childPath = path + "/" + keyword;
            if (SAME_INSTANCE_SINGLE_KEYWORDS.contains(keyword)) {
                checkChain(value, childPath);
            } else if (SAME_INSTANCE_ARRAY_KEYWORDS.contains(keyword) && value.isJsonArray()) {
                final var arr = value.asJsonArray();
                for (var i = 0; i < arr.size(); i++) {
                    checkChain(arr.get(i), childPath + "/" + i);
                }
            } else if (SchemaKeywords.DEPENDENT_SCHEMAS.equals(keyword) && value.isJsonObject()) {
                for (final var dependent : value.asJsonObject().entrySet()) {
                    checkChain(dependent.getValue(), childPath + "/" + dependent.getKey());
                }
            }
        }
    }

    private record RefTarget(JsonBaseElement node, String path) {
    }
}
