package io.github.christiancg.lwnrdb.testsupport;

import java.util.HashSet;
import tools.jackson.databind.JsonNode;

public final class JsonTrees {
    private JsonTrees() {
    }

    public static boolean equivalent(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) {
            return Double.compare(left.doubleValue(), right.doubleValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (!new HashSet<>(left.propertyNames()).equals(new HashSet<>(right.propertyNames()))) {
                return false;
            }
            return left.propertyNames().stream().allMatch(name -> equivalent(left.get(name), right.get(name)));
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (var i = 0; i < left.size(); i++) {
                if (!equivalent(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }
}
