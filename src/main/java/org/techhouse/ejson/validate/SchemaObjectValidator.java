package org.techhouse.ejson.validate;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

class SchemaObjectValidator {
    private final SchemaValidator nodeValidator;

    SchemaObjectValidator(SchemaValidator nodeValidator) {
        this.nodeValidator = nodeValidator;
    }

    void validateObject(JsonObject instance, JsonObject obj, JsonSchema schema, SchemaPath path, List<String> errors) {
        final var hidden = schema.hiddenKeyOf(instance);
        validateRequired(instance, hidden, obj, path, errors);
        validatePropertyCounts(instance, hidden, obj, path, errors);
        validateDependentRequired(instance, hidden, obj, path, errors);
        validateDependentSchemas(instance, hidden, obj, schema, path, errors);
        validatePropertyNames(instance, hidden, obj, schema, path, errors);
        validateProperties(instance, hidden, obj, schema, path, errors);
    }

    private static boolean has(JsonObject instance, String hidden, String name) {
        return !name.equals(hidden) && instance.has(name);
    }

    private static int visibleSize(JsonObject instance, String hidden) {
        return hidden == null ? instance.size() : instance.size() - 1;
    }

    private void validateRequired(JsonObject instance, String hidden, JsonObject obj, SchemaPath path,
            List<String> errors) {
        final var required = obj.get(SchemaKeywords.REQUIRED);
        if (required == null) {
            return;
        }
        for (final var element : required.asJsonArray()) {
            final var name = element.asJsonString().getValue();
            if (!has(instance, hidden, name)) {
                errors.add(path.describe() + ": missing required property '" + name + "'");
            }
        }
    }

    private void validatePropertyCounts(JsonObject instance, String hidden, JsonObject obj, SchemaPath path,
            List<String> errors) {
        final var size = visibleSize(instance, hidden);
        final var min = obj.get(SchemaKeywords.MIN_PROPERTIES);
        if (min != null && size < min.asJsonNumber().getValue().intValue()) {
            errors.add(path.describe() + ": object has fewer than " + min.asJsonNumber().getValue().intValue()
                    + " properties");
        }
        final var max = obj.get(SchemaKeywords.MAX_PROPERTIES);
        if (max != null && size > max.asJsonNumber().getValue().intValue()) {
            errors.add(path.describe() + ": object has more than " + max.asJsonNumber().getValue().intValue()
                    + " properties");
        }
    }

    private void validateDependentRequired(JsonObject instance, String hidden, JsonObject obj, SchemaPath path,
            List<String> errors) {
        final var dependentRequired = obj.get(SchemaKeywords.DEPENDENT_REQUIRED);
        if (dependentRequired == null) {
            return;
        }
        for (final var entry : dependentRequired.asJsonObject().entrySet()) {
            if (!has(instance, hidden, entry.getKey())) {
                continue;
            }
            for (final var dep : entry.getValue().asJsonArray()) {
                final var name = dep.asJsonString().getValue();
                if (!has(instance, hidden, name)) {
                    errors.add(path.describe() + ": property '" + entry.getKey() + "' requires '" + name + "'");
                }
            }
        }
    }

    private void validateDependentSchemas(JsonObject instance, String hidden, JsonObject obj, JsonSchema schema,
            SchemaPath path, List<String> errors) {
        final var dependentSchemas = obj.get(SchemaKeywords.DEPENDENT_SCHEMAS);
        if (dependentSchemas == null) {
            return;
        }
        for (final var entry : dependentSchemas.asJsonObject().entrySet()) {
            if (has(instance, hidden, entry.getKey())) {
                nodeValidator.validateNode(instance, entry.getValue(), schema, path, errors);
            }
        }
    }

    private void validatePropertyNames(JsonObject instance, String hidden, JsonObject obj, JsonSchema schema,
            SchemaPath path, List<String> errors) {
        final var propertyNames = obj.get(SchemaKeywords.PROPERTY_NAMES);
        if (propertyNames == null) {
            return;
        }
        for (final var entry : instance.entrySet()) {
            final var key = entry.getKey();
            if (!key.equals(hidden)) {
                nodeValidator.validateNode(new JsonString(key), propertyNames, schema, path.property(key), errors);
            }
        }
    }

    private void validateProperties(JsonObject instance, String hidden, JsonObject obj, JsonSchema schema,
            SchemaPath path, List<String> errors) {
        final var properties = obj.get(SchemaKeywords.PROPERTIES);
        final var patternProperties = obj.get(SchemaKeywords.PATTERN_PROPERTIES);
        final var additionalProperties = obj.get(SchemaKeywords.ADDITIONAL_PROPERTIES);
        if (properties == null && patternProperties == null && additionalProperties == null) {
            return;
        }
        for (final var entry : instance.entrySet()) {
            final var key = entry.getKey();
            if (key.equals(hidden)) {
                continue;
            }
            final var childPath = path.property(key);
            var covered = false;
            if (properties != null && properties.asJsonObject().has(key)) {
                covered = true;
                nodeValidator.validateNode(entry.getValue(), properties.asJsonObject().get(key), schema, childPath,
                        errors);
            }
            if (patternProperties != null) {
                for (final var pp : patternProperties.asJsonObject().entrySet()) {
                    if (SchemaPatterns.matches(pp.getKey(), key, schema.budget())) {
                        covered = true;
                        nodeValidator.validateNode(entry.getValue(), pp.getValue(), schema, childPath, errors);
                    }
                }
            }
            if (!covered && additionalProperties != null) {
                nodeValidator.validateNode(entry.getValue(), additionalProperties, schema, childPath, errors);
            }
        }
    }
}
