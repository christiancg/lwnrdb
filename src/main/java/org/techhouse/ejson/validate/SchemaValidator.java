package org.techhouse.ejson.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBaseElement.JsonType;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

// Validates a JSON value against a JsonSchema (draft 2020-12 subset). Keyword semantics follow the spec:
// a keyword that does not apply to the instance's type is simply skipped, and errors carry a
// JSON-Pointer-style path so the caller can point at the offending location.
public class SchemaValidator {

    public SchemaValidationResult validate(JsonBaseElement instance, JsonSchema schema) {
        final var errors = new ArrayList<String>();
        validateNode(instance, schema.getRoot(), schema, "", errors);
        return errors.isEmpty() ? SchemaValidationResult.valid() : SchemaValidationResult.invalid(errors);
    }

    private void validateNode(JsonBaseElement instance, JsonBaseElement schemaNode, JsonSchema schema, String path,
            List<String> errors) {
        if (schemaNode == null) {
            return;
        }
        if (schemaNode.getJsonType() == JsonType.BOOLEAN) {
            if (!schemaNode.asJsonBoolean().getValue()) {
                errors.add(at(path) + ": no value is allowed here");
            }
            return;
        }
        if (!schemaNode.isJsonObject()) {
            return;
        }
        final var obj = schemaNode.asJsonObject();
        if (obj.has(SchemaKeywords.REF)) {
            final var resolved = schema.resolveRef(obj.get(SchemaKeywords.REF).asJsonString().getValue());
            validateNode(instance, resolved, schema, path, errors);
        }
        validateType(instance, obj, path, errors);
        validateCustomType(instance, obj, path, errors);
        validateEnumAndConst(instance, obj, path, errors);
        validateApplicators(instance, obj, schema, path, errors);
        dispatchByType(instance, obj, schema, path, errors);
    }

    private void dispatchByType(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        switch (instance.getJsonType()) {
            case OBJECT -> validateObject(instance.asJsonObject(), obj, schema, path, errors);
            case ARRAY -> validateArray(instance.asJsonArray(), obj, schema, path, errors);
            case STRING, CUSTOM -> validateString(instance.asJsonString(), obj, path, errors);
            case NUMBER -> validateNumber(instance.asJsonNumber().getValue().doubleValue(), obj, path, errors);
            default -> {
                /* boolean / null: only the generic keywords above apply */ }
        }
    }

    private void validateType(JsonBaseElement instance, JsonObject obj, String path, List<String> errors) {
        final var type = obj.get(SchemaKeywords.TYPE);
        if (type == null) {
            return;
        }
        final boolean ok;
        if (type.isJsonString()) {
            ok = matchesType(instance, type.asJsonString().getValue());
        } else {
            var matched = false;
            for (final var element : type.asJsonArray()) {
                if (element.isJsonString() && matchesType(instance, element.asJsonString().getValue())) {
                    matched = true;
                    break;
                }
            }
            ok = matched;
        }
        if (!ok) {
            errors.add(at(path) + ": value does not match the required type");
        }
    }

    private static boolean matchesType(JsonBaseElement instance, String typeName) {
        return switch (typeName) {
            case SchemaKeywords.T_OBJECT -> instance.isJsonObject();
            case SchemaKeywords.T_ARRAY -> instance.isJsonArray();
            case SchemaKeywords.T_STRING -> instance.isJsonString();
            case SchemaKeywords.T_NUMBER -> instance.isJsonNumber();
            case SchemaKeywords.T_INTEGER ->
                instance.isJsonNumber() && instance.asJsonNumber().getValue().doubleValue() % 1.0 == 0;
            case SchemaKeywords.T_BOOLEAN -> instance.isJsonBoolean();
            case SchemaKeywords.T_NULL -> instance.isJsonNull();
            default -> false;
        };
    }

    private void validateCustomType(JsonBaseElement instance, JsonObject obj, String path, List<String> errors) {
        final var customType = obj.get(SchemaKeywords.CUSTOM_TYPE);
        if (customType == null) {
            return;
        }
        final var expected = customType.asJsonString().getValue();
        if (!instance.isJsonCustom() || !expected.equals(instance.asJsonCustom().getCustomTypeName())) {
            errors.add(at(path) + ": value is not of custom type '" + expected + "'");
        }
    }

    private void validateEnumAndConst(JsonBaseElement instance, JsonObject obj, String path, List<String> errors) {
        final var enumValues = obj.get(SchemaKeywords.ENUM);
        if (enumValues != null && enumValues.isJsonArray()) {
            var found = false;
            for (final var candidate : enumValues.asJsonArray()) {
                if (candidate.equals(instance)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(at(path) + ": value is not one of the permitted enum values");
            }
        }
        final var constValue = obj.get(SchemaKeywords.CONST);
        if (constValue != null && !constValue.equals(instance)) {
            errors.add(at(path) + ": value does not equal the required const");
        }
    }

    private void validateApplicators(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var allOf = obj.get(SchemaKeywords.ALL_OF);
        if (allOf != null) {
            final var arr = allOf.asJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                validateNode(instance, arr.get(i), schema, path, errors);
            }
        }
        validateAnyOf(instance, obj, schema, path, errors);
        validateOneOf(instance, obj, schema, path, errors);
        final var not = obj.get(SchemaKeywords.NOT);
        if (not != null && isValid(instance, not, schema)) {
            errors.add(at(path) + ": value must not match the 'not' schema");
        }
        validateConditional(instance, obj, schema, path, errors);
    }

    private void validateAnyOf(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var anyOf = obj.get(SchemaKeywords.ANY_OF);
        if (anyOf == null) {
            return;
        }
        for (final var sub : anyOf.asJsonArray()) {
            if (isValid(instance, sub, schema)) {
                return;
            }
        }
        errors.add(at(path) + ": value does not match any of the 'anyOf' schemas");
    }

    private void validateOneOf(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var oneOf = obj.get(SchemaKeywords.ONE_OF);
        if (oneOf == null) {
            return;
        }
        var matches = 0;
        for (final var sub : oneOf.asJsonArray()) {
            if (isValid(instance, sub, schema)) {
                matches++;
            }
        }
        if (matches != 1) {
            errors.add(at(path) + ": value must match exactly one of the 'oneOf' schemas (matched " + matches + ")");
        }
    }

    private void validateConditional(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var ifSchema = obj.get(SchemaKeywords.IF);
        if (ifSchema == null) {
            return;
        }
        if (isValid(instance, ifSchema, schema)) {
            final var thenSchema = obj.get(SchemaKeywords.THEN);
            if (thenSchema != null) {
                validateNode(instance, thenSchema, schema, path, errors);
            }
        } else {
            final var elseSchema = obj.get(SchemaKeywords.ELSE);
            if (elseSchema != null) {
                validateNode(instance, elseSchema, schema, path, errors);
            }
        }
    }

    private void validateObject(JsonObject instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
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
                errors.add(at(path) + ": missing required property '" + name + "'");
            }
        }
    }

    private void validatePropertyCounts(JsonObject instance, JsonObject obj, String path, List<String> errors) {
        final var min = obj.get(SchemaKeywords.MIN_PROPERTIES);
        if (min != null && instance.size() < min.asJsonNumber().getValue().intValue()) {
            errors.add(
                    at(path) + ": object has fewer than " + min.asJsonNumber().getValue().intValue() + " properties");
        }
        final var max = obj.get(SchemaKeywords.MAX_PROPERTIES);
        if (max != null && instance.size() > max.asJsonNumber().getValue().intValue()) {
            errors.add(at(path) + ": object has more than " + max.asJsonNumber().getValue().intValue() + " properties");
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
                    errors.add(at(path) + ": property '" + entry.getKey() + "' requires '" + name + "'");
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
                validateNode(instance, entry.getValue(), schema, path, errors);
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
            validateNode(new JsonString(entry.getKey()), propertyNames, schema, path + "/" + entry.getKey(), errors);
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
                validateNode(entry.getValue(), properties.asJsonObject().get(key), schema, childPath, errors);
            }
            if (patternProperties != null) {
                for (final var pp : patternProperties.asJsonObject().entrySet()) {
                    if (Pattern.compile(pp.getKey()).matcher(key).find()) {
                        covered = true;
                        validateNode(entry.getValue(), pp.getValue(), schema, childPath, errors);
                    }
                }
            }
            if (!covered && additionalProperties != null) {
                validateNode(entry.getValue(), additionalProperties, schema, childPath, errors);
            }
        }
    }

    private void validateArray(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var array = instance.asJsonArray();
        validateItemCounts(array, obj, path, errors);
        validateUniqueItems(array, obj, path, errors);
        validateItems(array, obj, schema, path, errors);
        validateContains(array, obj, schema, path, errors);
    }

    private void validateItemCounts(JsonArray array, JsonObject obj, String path, List<String> errors) {
        final var min = obj.get(SchemaKeywords.MIN_ITEMS);
        if (min != null && array.size() < min.asJsonNumber().getValue().intValue()) {
            errors.add(at(path) + ": array has fewer than " + min.asJsonNumber().getValue().intValue() + " items");
        }
        final var max = obj.get(SchemaKeywords.MAX_ITEMS);
        if (max != null && array.size() > max.asJsonNumber().getValue().intValue()) {
            errors.add(at(path) + ": array has more than " + max.asJsonNumber().getValue().intValue() + " items");
        }
    }

    private void validateUniqueItems(JsonArray array, JsonObject obj, String path, List<String> errors) {
        final var uniqueItems = obj.get(SchemaKeywords.UNIQUE_ITEMS);
        if (uniqueItems == null || !uniqueItems.asJsonBoolean().getValue()) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            for (int j = i + 1; j < array.size(); j++) {
                if (array.get(i).equals(array.get(j))) {
                    errors.add(at(path) + ": array items must be unique");
                    return;
                }
            }
        }
    }

    private void validateItems(JsonArray array, JsonObject obj, JsonSchema schema, String path, List<String> errors) {
        final var prefixItems = obj.get(SchemaKeywords.PREFIX_ITEMS);
        var prefixLength = 0;
        if (prefixItems != null) {
            final var prefix = prefixItems.asJsonArray();
            prefixLength = Math.min(prefix.size(), array.size());
            for (int i = 0; i < prefixLength; i++) {
                validateNode(array.get(i), prefix.get(i), schema, path + "/" + i, errors);
            }
        }
        final var items = obj.get(SchemaKeywords.ITEMS);
        if (items != null) {
            for (int i = prefixLength; i < array.size(); i++) {
                validateNode(array.get(i), items, schema, path + "/" + i, errors);
            }
        }
    }

    private void validateContains(JsonArray array, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var contains = obj.get(SchemaKeywords.CONTAINS);
        if (contains == null) {
            return;
        }
        var count = 0;
        for (final var item : array) {
            if (isValid(item, contains, schema)) {
                count++;
            }
        }
        final var min = obj.get(SchemaKeywords.MIN_CONTAINS);
        final var minContains = min != null ? min.asJsonNumber().getValue().intValue() : 1;
        if (count < minContains) {
            errors.add(at(path) + ": array must contain at least " + minContains + " matching item(s)");
        }
        final var max = obj.get(SchemaKeywords.MAX_CONTAINS);
        if (max != null && count > max.asJsonNumber().getValue().intValue()) {
            errors.add(at(path) + ": array must contain at most " + max.asJsonNumber().getValue().intValue()
                    + " matching item(s)");
        }
    }

    private void validateString(JsonString instance, JsonObject obj, String path, List<String> errors) {
        final var value = instance.getValue();
        final var length = value.codePointCount(0, value.length());
        final var min = obj.get(SchemaKeywords.MIN_LENGTH);
        if (min != null && length < min.asJsonNumber().getValue().intValue()) {
            errors.add(
                    at(path) + ": string is shorter than " + min.asJsonNumber().getValue().intValue() + " characters");
        }
        final var max = obj.get(SchemaKeywords.MAX_LENGTH);
        if (max != null && length > max.asJsonNumber().getValue().intValue()) {
            errors.add(
                    at(path) + ": string is longer than " + max.asJsonNumber().getValue().intValue() + " characters");
        }
        final var pattern = obj.get(SchemaKeywords.PATTERN);
        if (pattern != null && !Pattern.compile(pattern.asJsonString().getValue()).matcher(value).find()) {
            errors.add(at(path) + ": string does not match the required pattern");
        }
    }

    private void validateNumber(double value, JsonObject obj, String path, List<String> errors) {
        final var minimum = obj.get(SchemaKeywords.MINIMUM);
        if (minimum != null && value < minimum.asJsonNumber().getValue().doubleValue()) {
            errors.add(at(path) + ": value is less than the minimum");
        }
        final var maximum = obj.get(SchemaKeywords.MAXIMUM);
        if (maximum != null && value > maximum.asJsonNumber().getValue().doubleValue()) {
            errors.add(at(path) + ": value is greater than the maximum");
        }
        final var exclusiveMinimum = obj.get(SchemaKeywords.EXCLUSIVE_MINIMUM);
        if (exclusiveMinimum != null && value <= exclusiveMinimum.asJsonNumber().getValue().doubleValue()) {
            errors.add(at(path) + ": value is not greater than the exclusive minimum");
        }
        final var exclusiveMaximum = obj.get(SchemaKeywords.EXCLUSIVE_MAXIMUM);
        if (exclusiveMaximum != null && value >= exclusiveMaximum.asJsonNumber().getValue().doubleValue()) {
            errors.add(at(path) + ": value is not less than the exclusive maximum");
        }
        final var multipleOf = obj.get(SchemaKeywords.MULTIPLE_OF);
        if (multipleOf != null) {
            final var divisor = multipleOf.asJsonNumber().getValue().doubleValue();
            final var quotient = value / divisor;
            if (Math.abs(quotient - Math.rint(quotient)) > 1e-9) {
                errors.add(at(path) + ": value is not a multiple of " + divisor);
            }
        }
    }

    private boolean isValid(JsonBaseElement instance, JsonBaseElement schemaNode, JsonSchema schema) {
        final var errors = new ArrayList<String>();
        validateNode(instance, schemaNode, schema, "", errors);
        return errors.isEmpty();
    }

    private static String at(String path) {
        return path.isEmpty() ? "<root>" : path;
    }
}
