package org.techhouse.ejson.validate;

import java.math.BigDecimal;
import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

class SchemaScalarValidator {
    void validateString(JsonString instance, JsonObject obj, String path, List<String> errors) {
        final var value = instance.getValue();
        final var length = value.codePointCount(0, value.length());
        final var min = obj.get(SchemaKeywords.MIN_LENGTH);
        if (min != null && length < min.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": string is shorter than " + min.asJsonNumber().getValue().intValue()
                    + " characters");
        }
        final var max = obj.get(SchemaKeywords.MAX_LENGTH);
        if (max != null && length > max.asJsonNumber().getValue().intValue()) {
            errors.add(SchemaValidator.at(path) + ": string is longer than " + max.asJsonNumber().getValue().intValue()
                    + " characters");
        }
        final var pattern = obj.get(SchemaKeywords.PATTERN);
        if (pattern != null && !SchemaPatterns.matches(pattern.asJsonString().getValue(), value)) {
            errors.add(SchemaValidator.at(path) + ": string does not match the required pattern");
        }
    }

    void validateNumber(double value, JsonObject obj, String path, List<String> errors) {
        final var minimum = obj.get(SchemaKeywords.MINIMUM);
        if (minimum != null && value < minimum.asJsonNumber().getValue().doubleValue()) {
            errors.add(SchemaValidator.at(path) + ": value is less than the minimum");
        }
        final var maximum = obj.get(SchemaKeywords.MAXIMUM);
        if (maximum != null && value > maximum.asJsonNumber().getValue().doubleValue()) {
            errors.add(SchemaValidator.at(path) + ": value is greater than the maximum");
        }
        final var exclusiveMinimum = obj.get(SchemaKeywords.EXCLUSIVE_MINIMUM);
        if (exclusiveMinimum != null && value <= exclusiveMinimum.asJsonNumber().getValue().doubleValue()) {
            errors.add(SchemaValidator.at(path) + ": value is not greater than the exclusive minimum");
        }
        final var exclusiveMaximum = obj.get(SchemaKeywords.EXCLUSIVE_MAXIMUM);
        if (exclusiveMaximum != null && value >= exclusiveMaximum.asJsonNumber().getValue().doubleValue()) {
            errors.add(SchemaValidator.at(path) + ": value is not less than the exclusive maximum");
        }
        final var multipleOf = obj.get(SchemaKeywords.MULTIPLE_OF);
        if (multipleOf != null) {
            final var divisor = multipleOf.asJsonNumber().getValue().doubleValue();
            if (isNotMultiple(value, divisor)) {
                errors.add(SchemaValidator.at(path) + ": value is not a multiple of " + divisor);
            }
        }
    }

    private static boolean isNotMultiple(double value, double divisor) {
        return BigDecimal.valueOf(value).remainder(BigDecimal.valueOf(divisor)).signum() != 0;
    }
}
