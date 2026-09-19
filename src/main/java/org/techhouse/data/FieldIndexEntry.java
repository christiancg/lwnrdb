package org.techhouse.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;

public class FieldIndexEntry<T> extends CollectionScopedEntry implements Comparable<T> {
    private static final char ESCAPE_CHAR = '\\';
    private static final char SEPARATOR_CHAR = Globals.ID_SEPARATOR.charAt(0);
    private static final int ESCAPE_HEADROOM = 8;
    private T value;
    private Set<String> ids;

    public FieldIndexEntry(String databaseName, String collectionName, T value, Set<String> ids) {
        super(databaseName, collectionName);
        this.value = value;
        this.ids = ids;
    }

    public String toFileEntry() {
        final var escapedIds = new ArrayList<String>(ids.size());
        for (final var id : ids) {
            escapedIds.add(escapeIndexToken(id));
        }
        return fileKeyOf(value) + Globals.ID_SEPARATOR + String.join(Globals.ID_SEPARATOR, escapedIds);
    }

    public static String fileKeyOf(Object indexedValue) {
        return escapeIndexToken(indexKeyOf(indexedValue));
    }

    public static String escapeIndexToken(String raw) {
        var needsEscaping = false;
        for (var i = 0; i < raw.length(); i++) {
            final var current = raw.charAt(i);
            if (current == ESCAPE_CHAR || current == '\n' || current == '\r' || current == SEPARATOR_CHAR) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            return raw;
        }
        final var builder = new StringBuilder(raw.length() + ESCAPE_HEADROOM);
        for (var i = 0; i < raw.length(); i++) {
            final var current = raw.charAt(i);
            if (current == SEPARATOR_CHAR) {
                builder.append(ESCAPE_CHAR).append('s');
                continue;
            }
            switch (current) {
                case ESCAPE_CHAR -> builder.append(ESCAPE_CHAR).append(ESCAPE_CHAR);
                case '\n' -> builder.append(ESCAPE_CHAR).append('n');
                case '\r' -> builder.append(ESCAPE_CHAR).append('r');
                default -> builder.append(current);
            }
        }
        return builder.toString();
    }

    public static String unescapeIndexToken(String escaped) {
        if (escaped.indexOf(ESCAPE_CHAR) < 0) {
            return escaped;
        }
        final var builder = new StringBuilder(escaped.length());
        for (var i = 0; i < escaped.length(); i++) {
            final var current = escaped.charAt(i);
            if (current != ESCAPE_CHAR || i + 1 >= escaped.length()) {
                builder.append(current);
                continue;
            }
            final var next = escaped.charAt(++i);
            switch (next) {
                case ESCAPE_CHAR -> builder.append(ESCAPE_CHAR);
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 's' -> builder.append(SEPARATOR_CHAR);
                default -> builder.append(ESCAPE_CHAR).append(next);
            }
        }
        return builder.toString();
    }

    public static String indexKeyOf(Object indexedValue) {
        if (indexedValue instanceof JsonCustom<?> jc) {
            return jc.getValue();
        }
        if (indexedValue instanceof Number number) {
            final var asDouble = number.doubleValue();
            if (asDouble % 1 == 0 && asDouble >= Long.MIN_VALUE && asDouble <= Long.MAX_VALUE) {
                return Long.toString(number.longValue());
            }
            return Double.toString(asDouble);
        }
        return indexedValue.toString();
    }

    public static boolean sameIndexedValue(Object indexedValue, Object candidate) {
        if (indexedValue instanceof Number indexedNumber && candidate instanceof Number candidateNumber) {
            return Double.compare(indexedNumber.doubleValue(), candidateNumber.doubleValue()) == 0;
        }
        return Objects.equals(indexedValue, candidate);
    }

    public static <T> FieldIndexEntry<T> fromIndexFileEntry(String databaseName, String collectionName, String line,
            Class<T> tClass) {
        final var separatorIdx = line.indexOf(Globals.ID_SEPARATOR);
        final var strValue = unescapeIndexToken(line.substring(0, separatorIdx));
        Object value;
        if (Number.class.isAssignableFrom(tClass)) {
            value = Double.parseDouble(strValue);
        } else if (tClass == Boolean.class) {
            value = Boolean.parseBoolean(strValue);
        } else if (tClass == String.class) {
            value = strValue;
        } else {
            value = CustomTypeFactory.getCustomTypeInstance(strValue);
        }
        return new FieldIndexEntry<>(databaseName, collectionName, tClass.cast(value),
                parseIds(line, separatorIdx + Globals.ID_SEPARATOR.length()));
    }

    private static Set<String> parseIds(String line, int from) {
        final var separator = Globals.ID_SEPARATOR.charAt(0);
        var count = 0;
        for (var i = from; i < line.length(); i++) {
            if (line.charAt(i) == separator) {
                count++;
            }
        }
        if (count == 0) {
            final var single = HashSet.<String>newHashSet(1);
            single.add(unescapeIndexToken(line.substring(from)));
            return single;
        }
        final var ids = new ArrayList<String>(count + 1);
        var start = from;
        for (var i = from; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == separator) {
                ids.add(unescapeIndexToken(line.substring(start, i)));
                start = i + 1;
            }
        }
        while (!ids.isEmpty() && ids.getLast().isEmpty()) {
            ids.removeLast();
        }
        final var result = HashSet.<String>newHashSet(ids.size());
        result.addAll(ids);
        return result;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

    public Set<String> getIds() {
        return ids;
    }

    public void setIds(Set<String> ids) {
        this.ids = ids;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public int compareTo(T otherIndexValue) {
        Objects.requireNonNull(otherIndexValue);
        return switch (value) {
            case Number d -> Double.compare(d.doubleValue(), ((Number) otherIndexValue).doubleValue());
            case Boolean b -> b.compareTo((Boolean) otherIndexValue);
            case String s -> s.compareToIgnoreCase((String) otherIndexValue);
            case null -> 0;
            default -> {
                if (otherIndexValue instanceof JsonCustom<?>) {
                    @SuppressWarnings("unchecked")
                    final var ownValue = (JsonCustom<T>) value;
                    @SuppressWarnings("unchecked")
                    final var toCompareValue = (JsonCustom<T>) otherIndexValue;
                    yield ownValue.compareToCustom(toCompareValue);
                } else {
                    throw new IllegalStateException("Unexpected value: " + otherIndexValue);
                }
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof FieldIndexEntry<?> that))
            return false;
        return Objects.equals(databaseName, that.databaseName) && Objects.equals(collectionName, that.collectionName)
                && Objects.equals(value, that.value) && Objects.equals(ids, that.ids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(databaseName, collectionName, value, ids);
    }

    @Override
    public String toString() {
        return "FieldIndexEntry(databaseName=" + databaseName + ", collectionName=" + collectionName + ", value="
                + value + ", ids=" + ids + ")";
    }
}
