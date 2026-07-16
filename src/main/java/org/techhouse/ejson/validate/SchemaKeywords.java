package org.techhouse.ejson.validate;

import java.util.Set;

final class SchemaKeywords {
    private SchemaKeywords() {
    }

    static final String TYPE = "type";
    static final String ENUM = "enum";
    static final String CONST = "const";
    static final String MULTIPLE_OF = "multipleOf";
    static final String MAXIMUM = "maximum";
    static final String MINIMUM = "minimum";
    static final String EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    static final String EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    static final String MAX_LENGTH = "maxLength";
    static final String MIN_LENGTH = "minLength";
    static final String PATTERN = "pattern";
    static final String MAX_ITEMS = "maxItems";
    static final String MIN_ITEMS = "minItems";
    static final String UNIQUE_ITEMS = "uniqueItems";
    static final String MAX_CONTAINS = "maxContains";
    static final String MIN_CONTAINS = "minContains";
    static final String MAX_PROPERTIES = "maxProperties";
    static final String MIN_PROPERTIES = "minProperties";
    static final String REQUIRED = "required";
    static final String DEPENDENT_REQUIRED = "dependentRequired";
    static final String PROPERTIES = "properties";
    static final String PATTERN_PROPERTIES = "patternProperties";
    static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    static final String PROPERTY_NAMES = "propertyNames";
    static final String PREFIX_ITEMS = "prefixItems";
    static final String ITEMS = "items";
    static final String CONTAINS = "contains";
    static final String ALL_OF = "allOf";
    static final String ANY_OF = "anyOf";
    static final String ONE_OF = "oneOf";
    static final String NOT = "not";
    static final String IF = "if";
    static final String THEN = "then";
    static final String ELSE = "else";
    static final String DEPENDENT_SCHEMAS = "dependentSchemas";
    static final String REF = "$ref";
    static final String DEFS = "$defs";
    static final String FORMAT = "format";
    // EJson extension: asserts a field is one of the registered custom types (geo, vector, datetime, time).
    static final String CUSTOM_TYPE = "customType";

    static final String T_OBJECT = "object";
    static final String T_ARRAY = "array";
    static final String T_STRING = "string";
    static final String T_NUMBER = "number";
    static final String T_INTEGER = "integer";
    static final String T_BOOLEAN = "boolean";
    static final String T_NULL = "null";

    static final Set<String> TYPE_NAMES = Set.of(T_OBJECT, T_ARRAY, T_STRING, T_NUMBER, T_INTEGER, T_BOOLEAN, T_NULL);

    // Keywords accepted but non-constraining (annotations / identifiers). Any value is tolerated.
    static final Set<String> ANNOTATIONS = Set.of("$schema", "$id", "$anchor", "$comment", "title", "description",
            "default", "examples", "readOnly", "writeOnly", "deprecated");

    // Known 2020-12 keywords that this validator does not implement. Rejected by the meta-validator so a
    // schema author is never misled into believing an unenforced constraint is applied.
    static final Set<String> UNSUPPORTED = Set.of("unevaluatedProperties", "unevaluatedItems", "$dynamicRef",
            "$dynamicAnchor", "$vocabulary", "$recursiveRef", "$recursiveAnchor");
}
