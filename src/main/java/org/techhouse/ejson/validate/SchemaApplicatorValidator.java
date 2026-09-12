package org.techhouse.ejson.validate;

import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;

class SchemaApplicatorValidator {
    private final SchemaValidator nodeValidator;

    SchemaApplicatorValidator(SchemaValidator nodeValidator) {
        this.nodeValidator = nodeValidator;
    }

    void validateApplicators(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var allOf = obj.get(SchemaKeywords.ALL_OF);
        if (allOf != null) {
            final var arr = allOf.asJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                nodeValidator.validateNode(instance, arr.get(i), schema, path, errors);
            }
        }
        validateAnyOf(instance, obj, schema, path, errors);
        validateOneOf(instance, obj, schema, path, errors);
        final var not = obj.get(SchemaKeywords.NOT);
        if (not != null && nodeValidator.isValid(instance, not, schema)) {
            errors.add(SchemaValidator.at(path) + ": value must not match the 'not' schema");
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
            if (nodeValidator.isValid(instance, sub, schema)) {
                return;
            }
        }
        errors.add(SchemaValidator.at(path) + ": value does not match any of the 'anyOf' schemas");
    }

    private void validateOneOf(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var oneOf = obj.get(SchemaKeywords.ONE_OF);
        if (oneOf == null) {
            return;
        }
        var matches = 0;
        for (final var sub : oneOf.asJsonArray()) {
            if (nodeValidator.isValid(instance, sub, schema)) {
                matches++;
            }
        }
        if (matches != 1) {
            errors.add(SchemaValidator.at(path) + ": value must match exactly one of the 'oneOf' schemas (matched "
                    + matches + ")");
        }
    }

    private void validateConditional(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path,
            List<String> errors) {
        final var ifSchema = obj.get(SchemaKeywords.IF);
        if (ifSchema == null) {
            return;
        }
        if (nodeValidator.isValid(instance, ifSchema, schema)) {
            final var thenSchema = obj.get(SchemaKeywords.THEN);
            if (thenSchema != null) {
                nodeValidator.validateNode(instance, thenSchema, schema, path, errors);
            }
        } else {
            final var elseSchema = obj.get(SchemaKeywords.ELSE);
            if (elseSchema != null) {
                nodeValidator.validateNode(instance, elseSchema, schema, path, errors);
            }
        }
    }
}
