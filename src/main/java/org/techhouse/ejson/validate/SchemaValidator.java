package org.techhouse.ejson.validate;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBaseElement.JsonType;
import org.techhouse.ejson.elements.JsonObject;

public class SchemaValidator {
    private final SchemaApplicatorValidator applicatorValidator = new SchemaApplicatorValidator(this);
    private final SchemaObjectValidator objectValidator = new SchemaObjectValidator(this);
    private final SchemaArrayValidator arrayValidator = new SchemaArrayValidator(this);
    private final SchemaScalarValidator scalarValidator = new SchemaScalarValidator();

    public SchemaValidationResult validate(JsonBaseElement instance, JsonSchema schema) {
        final var errors = new CappedErrors();
        validateNode(instance, schema.getRoot(), schema, SchemaPath.ROOT, errors);
        return errors.isEmpty() ? SchemaValidationResult.valid() : SchemaValidationResult.invalid(errors.reported());
    }

    void validateNode(JsonBaseElement instance, JsonBaseElement schemaNode, JsonSchema schema, SchemaPath path,
            List<String> errors) {
        schema.charge();
        if (schemaNode == null) {
            return;
        }
        if (schemaNode.getJsonType() == JsonType.BOOLEAN) {
            if (!schemaNode.asJsonBoolean().getValue()) {
                errors.add(path.describe() + ": no value is allowed here");
            }
            return;
        }
        if (!schemaNode.isJsonObject()) {
            return;
        }
        final var obj = schemaNode.asJsonObject();
        if (obj.has(SchemaKeywords.REF)) {
            validateRef(instance, obj, schema, path, errors);
        }
        validateType(instance, obj, path, errors);
        validateCustomType(instance, obj, path, errors);
        validateEnumAndConst(instance, obj, schema, path, errors);
        applicatorValidator.validateApplicators(instance, obj, schema, path, errors);
        dispatchByType(instance, obj, schema, path, errors);
    }

    private void validateRef(JsonBaseElement instance, JsonObject obj, JsonSchema schema, SchemaPath path,
            List<String> errors) {
        final var resolved = schema.resolveRef(obj.get(SchemaKeywords.REF).asJsonString().getValue());
        if (!schema.beginRefExpansion(instance, resolved)) {
            errors.add(path.describe() + ": the schema's '" + SchemaKeywords.REF
                    + "' forms a reference cycle and cannot be evaluated");
            return;
        }
        try {
            validateNode(instance, resolved, schema, path, errors);
        } finally {
            schema.endRefExpansion(instance, resolved);
        }
    }

    private void dispatchByType(JsonBaseElement instance, JsonObject obj, JsonSchema schema, SchemaPath path,
            List<String> errors) {
        switch (instance.getJsonType()) {
            case OBJECT -> objectValidator.validateObject(instance.asJsonObject(), obj, schema, path, errors);
            case ARRAY -> arrayValidator.validateArray(instance.asJsonArray(), obj, schema, path, errors);
            case STRING, CUSTOM -> scalarValidator.validateString(instance.asJsonString(), obj, schema, path, errors);
            case NUMBER -> scalarValidator.validateNumber(instance.asJsonNumber().doubleValue(), obj, path, errors);
            default -> {
                /* boolean / null: only the generic keywords above apply */ }
        }
    }

    private void validateType(JsonBaseElement instance, JsonObject obj, SchemaPath path, List<String> errors) {
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
            errors.add(path.describe() + ": value does not match the required type");
        }
    }

    private static boolean matchesType(JsonBaseElement instance, String typeName) {
        return switch (typeName) {
            case SchemaKeywords.T_OBJECT -> instance.isJsonObject();
            case SchemaKeywords.T_ARRAY -> instance.isJsonArray();
            case SchemaKeywords.T_STRING -> instance.isJsonString();
            case SchemaKeywords.T_NUMBER -> instance.isJsonNumber();
            case SchemaKeywords.T_INTEGER ->
                instance.isJsonNumber() && instance.asJsonNumber().doubleValue() % 1.0 == 0;
            case SchemaKeywords.T_BOOLEAN -> instance.isJsonBoolean();
            case SchemaKeywords.T_NULL -> instance.isJsonNull();
            default -> false;
        };
    }

    private void validateCustomType(JsonBaseElement instance, JsonObject obj, SchemaPath path, List<String> errors) {
        final var customType = obj.get(SchemaKeywords.CUSTOM_TYPE);
        if (customType == null) {
            return;
        }
        final var expected = customType.asJsonString().getValue();
        if (!instance.isJsonCustom() || !expected.equals(instance.asJsonCustom().getCustomTypeName())) {
            errors.add(path.describe() + ": value is not of custom type '" + expected + "'");
        }
    }

    private void validateEnumAndConst(JsonBaseElement instance, JsonObject obj, JsonSchema schema, SchemaPath path,
            List<String> errors) {
        final var enumValues = obj.get(SchemaKeywords.ENUM);
        if (enumValues != null && enumValues.isJsonArray()) {
            final var comparable = schema.comparable(instance);
            var found = false;
            for (final var candidate : enumValues.asJsonArray()) {
                schema.charge();
                if (candidate.equals(comparable)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(path.describe() + ": value is not one of the permitted enum values");
            }
        }
        final var constValue = obj.get(SchemaKeywords.CONST);
        if (constValue != null && !constValue.equals(schema.comparable(instance))) {
            errors.add(path.describe() + ": value does not equal the required const");
        }
    }

    boolean isValid(JsonBaseElement instance, JsonBaseElement schemaNode, JsonSchema schema) {
        final var errors = new CappedErrors();
        validateNode(instance, schemaNode, schema, SchemaPath.ROOT, errors);
        return errors.isEmpty();
    }

    static String at(String path) {
        return path.isEmpty() ? "<root>" : path;
    }
}
