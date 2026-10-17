package org.techhouse.unit.ejson.validate;

public final class DiamondSchemas {
    private DiamondSchemas() {
    }

    public static String chain(int depth) {
        return withDefs("\"$ref\":\"#/$defs/d0\"", depth);
    }

    public static String behindProperty(String property, int depth) {
        return withDefs("\"type\":\"object\",\"properties\":{\"" + property + "\":{\"$ref\":\"#/$defs/d0\"}}", depth);
    }

    private static String withDefs(String entry, int depth) {
        final var schema = new StringBuilder(128 + 80 * depth);
        schema.append('{').append(entry).append(",\"$defs\":{");
        for (var i = 0; i < depth; i++) {
            final var next = "{\"$ref\":\"#/$defs/d" + (i + 1) + "\"}";
            schema.append("\"d").append(i).append("\":{\"allOf\":[").append(next).append(',').append(next)
                    .append("]},");
        }
        return schema.append("\"d").append(depth).append("\":{\"type\":\"object\"}}}").toString();
    }

    public static String redosPatternSchema() {
        return "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"pattern\":\"(x+x+)+y\"}}}";
    }

    public static String hostileName() {
        return "x".repeat(800) + "!";
    }
}
