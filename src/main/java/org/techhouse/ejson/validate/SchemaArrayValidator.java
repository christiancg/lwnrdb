package org.techhouse.ejson.validate;

import java.util.List;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;

class SchemaArrayValidator {
    private final SchemaValidator nodeValidator;

    SchemaArrayValidator(SchemaValidator nodeValidator) {
        this.nodeValidator = nodeValidator;
    }

    void validateArray(JsonBaseElement instance, JsonObject obj, JsonSchema schema, String path, List<String> errors) {
        final var array = instance.asJsonArray();
        validateItemCounts(array, obj, path, errors);
        validateUniqueItems(array, obj, path, errors);
        validateItems(array, obj, schema, path, errors);
        validateContains(array, obj, schema, path, errors);
    }

    private void validateItemCounts(JsonArray array, JsonObject obj, String path, List<String> errors) {
        final var min = obj.get(SchemaKeywords.MIN_ITEMS);
        if (min != null && array.size() < min.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": array has fewer than " + min.asJsonNumber().getValue().intValue()
                    + " items");
        }
        final var max = obj.get(SchemaKeywords.MAX_ITEMS);
        if (max != null && array.size() > max.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": array has more than " + max.asJsonNumber().getValue().intValue()
                    + " items");
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
                    errors.add(SchemaValidator.at(path) + ": array items must be unique");
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
                nodeValidator.validateNode(array.get(i), prefix.get(i), schema, path + "/" + i, errors);
            }
        }
        final var items = obj.get(SchemaKeywords.ITEMS);
        if (items != null) {
            for (int i = prefixLength; i < array.size(); i++) {
                nodeValidator.validateNode(array.get(i), items, schema, path + "/" + i, errors);
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
            if (nodeValidator.isValid(item, contains, schema)) {
                count++;
            }
        }
        final var min = obj.get(SchemaKeywords.MIN_CONTAINS);
        final var minContains = min != null ? min.asJsonNumber().getValue().intValue() : 1;
        if (count < minContains) {
            errors.add(SchemaValidator.at(path) + ": array must contain at least " + minContains + " matching item(s)");
        }
        final var max = obj.get(SchemaKeywords.MAX_CONTAINS);
        if (max != null && count > max.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": array must contain at most "
                    + max.asJsonNumber().getValue().intValue() + " matching item(s)");
        }
    }
}
