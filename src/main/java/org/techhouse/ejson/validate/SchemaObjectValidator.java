package org.techhouse.ejson.validate;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

class SchemaObjectValidator {
    private final SchemaValidator nodeValidator;

    SchemaObjectValidator(SchemaValidator nodeValidator) {
        this.nodeValidator = nodeValidator;
    }

    void validateObject(JsonObject instance, JsonObject obj, JsonSchema schema, String path, List<String> errors) {
        validateRequired(instance, obj, path, errors);
        validatePropertyCounts(instance, obj, path, errors);
        validateDependentRequired(instance, obj, path, errors);
        validateDependentSchemas(instance, obj, schema, path, errors);
        validatePropertyNames(instance, obj, schema, path, errors);
        validateProperties(instance, obj, schema, path, errors);
    }

    private void validateRequired(JsonObject instance, JsonObject obj, String path, List<String> errors) {
        final var required = obj.get(SchemaKeywords.REQUIRED);
        if (required == null) {
            return;
        }
        for (final var element : required.asJsonArray()) {
            final var name = element.asJsonString().getValue();
            if (!instance.has(name)) {
                errors.add(SchemaValidator.at(path) + ": missing required property '" + name + "'");
            }
        }
    }

    private void validatePropertyCounts(JsonObject instance, JsonObject obj, String path, List<String> errors) {
        final var min = obj.get(SchemaKeywords.MIN_PROPERTIES);
        if (min != null && instance.size() < min.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": object has fewer than " + min.asJsonNumber().getValue().intValue()
                    + " properties");
        }
        final var max = obj.get(SchemaKeywords.MAX_PROPERTIES);
        if (max != null && instance.size() > max.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": object has more than " + max.asJsonNumber().getValue().intValue()
                    + " properties");
        }
    }

    private void validateDependentRequired(JsonObject instance, JsonObject obj, String path, List<String> errors) {
        final var dependentRequired = obj.get(SchemaKeywords.DEPENDENT_REQUIRED);
        if (dependentRequired == null) {
            return;
        }
        for (final var entry : dependentRequired.asJsonObject().entrySet()) {
            if (!instance.has(entry.getKey())) {
                continue;
            }
            for (final var dep : entry.getValue().asJsonArray()) {
                final var name = dep.asJsonString().getValue();
                if (!instance.has(name)) {
                    errors.add(
                            SchemaValidator.at(path) + ": property '" + entry.getKey() + "' requires '" + name + "'");
                }
            }
        }
    }

    private void validateDependentSchemas(JsonObject instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var dependentSchemas = obj.get(SchemaKeywords.DEPENDENT_SCHEMAS);
        if (dependentSchemas == null) {
            return;
        }
        for (final var entry : dependentSchemas.asJsonObject().entrySet()) {
            if (instance.has(entry.getKey())) {
                nodeValidator.validateNode(instance, entry.getValue(), schema, path, errors);
            }
        }
    }

    private void validatePropertyNames(JsonObject instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var propertyNames = obj.get(SchemaKeywords.PROPERTY_NAMES);
        if (propertyNames == null) {
            return;
        }
        for (final var entry : instance.entrySet()) {
            nodeValidator.validateNode(new JsonString(entry.getKey()), propertyNames, schema,
                    path + "/" + entry.getKey(), errors);
        }
    }

    private void validateProperties(JsonObject instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var properties = obj.get(SchemaKeywords.PROPERTIES);
        final var patternProperties = obj.get(SchemaKeywords.PATTERN_PROPERTIES);
        final var additionalProperties = obj.get(SchemaKeywords.ADDITIONAL_PROPERTIES);
        for (final var entry : instance.entrySet()) {
            final var key = entry.getKey();
            final var childPath = path + "/" + key;
            var covered = false;
            if (properties != null && properties.asJsonObject().has(key)) {
                covered = true;
                nodeValidator.validateNode(entry.getValue(), properties.asJsonObject().get(key), schema, childPath,
                        errors);
            }
            if (patternProperties != null) {
                for (final var pp : patternProperties.asJsonObject().entrySet()) {
                    if (SchemaPatterns.compile(pp.getKey()).matcher(key).find()) {
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
