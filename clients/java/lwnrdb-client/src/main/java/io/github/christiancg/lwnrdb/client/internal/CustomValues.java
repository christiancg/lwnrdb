package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.values.CustomType;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.regex.Pattern;

public final class CustomValues {
    public static final Pattern DETECTION = Pattern.compile("^#[a-zA-Z0-9]{3,20}\\(.*\\)$");
    private static final String SEPARATOR = ",";

    private CustomValues() {
    }

    public static boolean looksCustom(String text) {
        return !text.isEmpty() && text.charAt(0) == '#' && DETECTION.matcher(text).matches();
    }

    public static String format(GeoPoint point) {
        return wrap(CustomType.GEO, point.lat() + SEPARATOR + point.lng());
    }

    public static String format(Vector vector) {
        final var body = new StringBuilder();
        for (var i = 0; i < vector.dimensions(); i++) {
            if (i > 0) {
                body.append(SEPARATOR);
            }
            body.append(vector.component(i));
        }
        return wrap(CustomType.VECTOR, body.toString());
    }

    public static String format(LocalDateTime dateTime) {
        return wrap(CustomType.DATETIME, dateTime.toString());
    }

    public static String format(LocalTime time) {
        return wrap(CustomType.TIME, time.toString());
    }

    public static GeoPoint parseGeo(String text) {
        final var parts = body(CustomType.GEO, text).split(SEPARATOR, -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("a geo value holds two coordinates: " + text);
        }
        return GeoPoint.of(Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip()));
    }

    public static Vector parseVector(String text) {
        final var parts = body(CustomType.VECTOR, text).split(SEPARATOR, -1);
        final var components = new double[parts.length];
        for (var i = 0; i < parts.length; i++) {
            components[i] = Double.parseDouble(parts[i].strip());
        }
        return Vector.of(components);
    }

    public static LocalDateTime parseDateTime(String text) {
        return LocalDateTime.parse(body(CustomType.DATETIME, text));
    }

    public static LocalTime parseTime(String text) {
        return LocalTime.parse(body(CustomType.TIME, text));
    }

    private static String wrap(CustomType type, String body) {
        return "#" + type.wireName() + "(" + body + ")";
    }

    private static String body(CustomType type, String text) {
        final var prefix = "#" + type.wireName() + "(";
        if (!text.startsWith(prefix) || !text.endsWith(")")) {
            throw new IllegalArgumentException("not a " + type.wireName() + " value: " + text);
        }
        return text.substring(prefix.length(), text.length() - 1);
    }
}
