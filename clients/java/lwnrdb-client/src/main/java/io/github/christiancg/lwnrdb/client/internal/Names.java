package io.github.christiancg.lwnrdb.client.internal;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Names {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{3,64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Names() {
    }

    public static String database(String name) {
        return check(name, NAME, "database name", "3-64 characters from A-Z a-z 0-9 _ -");
    }

    public static String collection(String name) {
        return check(name, NAME, "collection name", "3-64 characters from A-Z a-z 0-9 _ -");
    }

    public static String id(String id) {
        return check(id, ID, "_id", "1-64 characters from A-Z a-z 0-9 _ -");
    }

    private static String check(String value, Pattern pattern, String label, String rule) {
        Objects.requireNonNull(value, label);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label + " '" + value + "': it must be " + rule);
        }
        return value;
    }
}
