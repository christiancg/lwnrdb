package org.techhouse.ejson.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBaseElement.JsonType;
import org.techhouse.ejson.elements.JsonString;

// Validates that a JSON value is itself a well-formed JSON Schema (draft 2020-12) for the subset this
// engine supports: every keyword must carry a value of the correct shape, nested schemas are checked
// recursively, and any known-but-unimplemented keyword is rejected (see SchemaKeywords.UNSUPPORTED).
public class MetaSchemaValidator {

    public SchemaValidationResult validate(JsonBaseElement schema) {
        final var errors = new ArrayList<String>();
        final var warnings = new ArrayList<String>();
        checkSchemaNode(schema, "", errors, warnings);
        return SchemaValidationResult.of(errors, warnings);
    }

    private void checkSchemaNode(JsonBaseElement node, String path, List<String> errors, List<String> warnings) {
        if (node == null) {
            errors.add(at(path) + ": schema is missing");
            return;
        }
        if (node.getJsonType() == JsonType.BOOLEAN) {
            return;
        }
        if (!node.isJsonObject()) {
            errors.add(at(path) + ": a schema must be an object or a boolean");
            return;
        }
        for (final var entry : node.asJsonObject().entrySet()) {
            checkKeyword(entry.getKey(), entry.getValue(), path, errors, warnings);
        }
    }

    private void checkKeyword(String keyword, JsonBaseElement value, String path, List<String> errors,
            List<String> warnings) {
        if (SchemaKeywords.UNSUPPORTED.contains(keyword)) {
            errors.add(at(path + "/" + keyword) + ": keyword '" + keyword + "' is not supported");
            return;
        }
        final var kwPath = path + "/" + keyword;
        switch (keyword) {
            case SchemaKeywords.TYPE -> checkType(value, kwPath, errors);
            case SchemaKeywords.CUSTOM_TYPE -> checkCustomType(value, kwPath, errors);
            case SchemaKeywords.ENUM -> checkNonEmptyArray(value, kwPath, errors);
            case SchemaKeywords.CONST -> {
                /* any value is allowed */ }
            case SchemaKeywords.MULTIPLE_OF -> checkPositiveNumber(value, kwPath, errors);
            case SchemaKeywords.MAXIMUM, SchemaKeywords.MINIMUM, SchemaKeywords.EXCLUSIVE_MAXIMUM,
                    SchemaKeywords.EXCLUSIVE_MINIMUM ->
                checkNumber(value, kwPath, errors);
            case SchemaKeywords.MAX_LENGTH, SchemaKeywords.MIN_LENGTH, SchemaKeywords.MAX_ITEMS,
                    SchemaKeywords.MIN_ITEMS, SchemaKeywords.MAX_CONTAINS, SchemaKeywords.MIN_CONTAINS,
                    SchemaKeywords.MAX_PROPERTIES, SchemaKeywords.MIN_PROPERTIES ->
                checkNonNegativeInteger(value, kwPath, errors);
            case SchemaKeywords.PATTERN, SchemaKeywords.FORMAT -> checkPattern(value, keyword, kwPath, errors);
            case SchemaKeywords.UNIQUE_ITEMS -> checkBoolean(value, kwPath, errors);
            case SchemaKeywords.REQUIRED -> checkStringArray(value, kwPath, errors);
            case SchemaKeywords.DEPENDENT_REQUIRED -> checkDependentRequired(value, kwPath, errors);
            case SchemaKeywords.PROPERTIES, SchemaKeywords.DEPENDENT_SCHEMAS, SchemaKeywords.DEFS ->
                checkSchemaMap(value, kwPath, errors, warnings);
            case SchemaKeywords.PATTERN_PROPERTIES -> checkPatternProperties(value, kwPath, errors, warnings);
            case SchemaKeywords.ADDITIONAL_PROPERTIES, SchemaKeywords.PROPERTY_NAMES, SchemaKeywords.ITEMS,
                    SchemaKeywords.CONTAINS, SchemaKeywords.NOT, SchemaKeywords.IF, SchemaKeywords.THEN,
                    SchemaKeywords.ELSE ->
                checkSchemaNode(value, kwPath, errors, warnings);
            case SchemaKeywords.PREFIX_ITEMS, SchemaKeywords.ALL_OF, SchemaKeywords.ANY_OF, SchemaKeywords.ONE_OF ->
                checkSchemaArray(value, kwPath, errors, warnings);
            case SchemaKeywords.REF -> checkString(value, kwPath, errors);
            default -> {
                if (!SchemaKeywords.ANNOTATIONS.contains(keyword)) {
                    warnings.add(at(kwPath) + ": unrecognized keyword '" + keyword + "' is ignored");
                }
            }
        }
    }

    private void checkType(JsonBaseElement value, String path, List<String> errors) {
        if (value.isJsonString()) {
            checkTypeName(value.asJsonString(), path, errors);
        } else if (value.isJsonArray()) {
            final var arr = value.asJsonArray();
            if (arr.isEmpty()) {
                errors.add(at(path) + ": 'type' array must not be empty");
            }
            for (final var element : arr) {
                if (element.isJsonString()) {
                    checkTypeName(element.asJsonString(), path, errors);
                } else {
                    errors.add(at(path) + ": 'type' array entries must be strings");
                }
            }
        } else {
            errors.add(at(path) + ": 'type' must be a string or an array of strings");
        }
    }

    private void checkTypeName(JsonString value, String path, List<String> errors) {
        if (!SchemaKeywords.TYPE_NAMES.contains(value.getValue())) {
            errors.add(at(path) + ": unknown type '" + value.getValue() + "'");
        }
    }

    private void checkCustomType(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonString()) {
            errors.add(at(path) + ": 'customType' must be a string");
            return;
        }
        final var name = value.asJsonString().getValue();
        if (!CustomTypeFactory.getCustomTypes().containsKey(name)) {
            errors.add(at(path) + ": unknown custom type '" + name + "'");
        }
    }

    private void checkDependentRequired(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonObject()) {
            errors.add(at(path) + ": 'dependentRequired' must be an object");
            return;
        }
        for (final var entry : value.asJsonObject().entrySet()) {
            checkStringArray(entry.getValue(), path + "/" + entry.getKey(), errors);
        }
    }

    private void checkSchemaMap(JsonBaseElement value, String path, List<String> errors, List<String> warnings) {
        if (!value.isJsonObject()) {
            errors.add(at(path) + ": must be an object of schemas");
            return;
        }
        for (final var entry : value.asJsonObject().entrySet()) {
            checkSchemaNode(entry.getValue(), path + "/" + entry.getKey(), errors, warnings);
        }
    }

    private void checkPatternProperties(JsonBaseElement value, String path, List<String> errors,
            List<String> warnings) {
        if (!value.isJsonObject()) {
            errors.add(at(path) + ": 'patternProperties' must be an object");
            return;
        }
        for (final var entry : value.asJsonObject().entrySet()) {
            try {
                Pattern.compile(entry.getKey());
            } catch (PatternSyntaxException e) {
                errors.add(at(path) + ": invalid regular expression key '" + entry.getKey() + "'");
            }
            checkSchemaNode(entry.getValue(), path + "/" + entry.getKey(), errors, warnings);
        }
    }

    private void checkSchemaArray(JsonBaseElement value, String path, List<String> errors, List<String> warnings) {
        if (!value.isJsonArray()) {
            errors.add(at(path) + ": must be an array of schemas");
            return;
        }
        final var arr = value.asJsonArray();
        if (arr.isEmpty()) {
            errors.add(at(path) + ": must be a non-empty array of schemas");
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            checkSchemaNode(arr.get(i), path + "/" + i, errors, warnings);
        }
    }

    private void checkNonEmptyArray(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonArray()) {
            errors.add(at(path) + ": must be an array");
        } else if (value.asJsonArray().isEmpty()) {
            errors.add(at(path) + ": must not be empty");
        }
    }

    private void checkStringArray(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonArray()) {
            errors.add(at(path) + ": must be an array of strings");
            return;
        }
        final var seen = new HashSet<String>();
        for (final var element : value.asJsonArray()) {
            if (!element.isJsonString()) {
                errors.add(at(path) + ": array entries must be strings");
            } else if (!seen.add(element.asJsonString().getValue())) {
                errors.add(at(path) + ": array entries must be unique");
            }
        }
    }

    private void checkNumber(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonNumber()) {
            errors.add(at(path) + ": must be a number");
        }
    }

    private void checkPositiveNumber(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonNumber()) {
            errors.add(at(path) + ": must be a number");
        } else if (value.asJsonNumber().getValue().doubleValue() <= 0) {
            errors.add(at(path) + ": must be greater than 0");
        }
    }

    private void checkNonNegativeInteger(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonNumber()) {
            errors.add(at(path) + ": must be a non-negative integer");
            return;
        }
        final var number = value.asJsonNumber().getValue().doubleValue();
        if (number < 0 || number % 1.0 != 0) {
            errors.add(at(path) + ": must be a non-negative integer");
        }
    }

    private void checkBoolean(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonBoolean()) {
            errors.add(at(path) + ": must be a boolean");
        }
    }

    private void checkString(JsonBaseElement value, String path, List<String> errors) {
        if (!value.isJsonString()) {
            errors.add(at(path) + ": must be a string");
        }
    }

    private void checkPattern(JsonBaseElement value, String keyword, String path, List<String> errors) {
        if (!value.isJsonString()) {
            errors.add(at(path) + ": '" + keyword + "' must be a string");
            return;
        }
        if (SchemaKeywords.PATTERN.equals(keyword)) {
            try {
                Pattern.compile(value.asJsonString().getValue());
            } catch (PatternSyntaxException e) {
                errors.add(at(path) + ": invalid regular expression");
            }
        }
    }

    private static String at(String path) {
        return path.isEmpty() ? "<root>" : path;
    }
}
