package org.techhouse.ejson.validate;

import java.util.List;

public final class SchemaValidationResult {
    private static final SchemaValidationResult VALID = new SchemaValidationResult(true, List.of(), List.of());
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;

    private SchemaValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        this.valid = valid;
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    public static SchemaValidationResult valid() {
        return VALID;
    }

    public static SchemaValidationResult invalid(List<String> errors) {
        return new SchemaValidationResult(false, errors, List.of());
    }

    public static SchemaValidationResult invalid(String error) {
        return new SchemaValidationResult(false, List.of(error), List.of());
    }

    // Builds a result whose validity is decided solely by the errors; warnings never fail validation.
    public static SchemaValidationResult of(List<String> errors, List<String> warnings) {
        if (errors.isEmpty() && warnings.isEmpty()) {
            return VALID;
        }
        return new SchemaValidationResult(errors.isEmpty(), errors, warnings);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
