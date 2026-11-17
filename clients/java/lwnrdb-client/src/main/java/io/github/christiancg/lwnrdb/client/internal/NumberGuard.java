package io.github.christiancg.lwnrdb.client.internal;

import java.math.BigInteger;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class NumberGuard {
    public static final long MAX_EXACT_INTEGER = 1L << 53;
    private static final BigInteger MAX_EXACT = BigInteger.valueOf(MAX_EXACT_INTEGER);

    private NumberGuard() {
    }

    public static void check(JsonNode node) {
        check(node, "");
    }

    private static void check(JsonNode node, String path) {
        if (node.isObject()) {
            for (final Map.Entry<String, JsonNode> member : node.properties()) {
                check(member.getValue(), path.isEmpty() ? member.getKey() : path + "." + member.getKey());
            }
        } else if (node.isArray()) {
            var index = 0;
            for (final var item : node.values()) {
                check(item, path + "[" + index++ + "]");
            }
        } else if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw new IllegalArgumentException(label(path) + " is not a finite number: " + node.doubleValue());
        } else if (node.isIntegralNumber() && node.bigIntegerValue().abs().compareTo(MAX_EXACT) > 0) {
            throw new IllegalArgumentException(
                    label(path) + " exceeds 2^53, which the server stores as a double;" + " store it as a string");
        }
    }

    private static String label(String path) {
        return path.isEmpty() ? "the value" : "field '" + path + "'";
    }
}
