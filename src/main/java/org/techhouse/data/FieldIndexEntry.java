package org.techhouse.data;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;

public class FieldIndexEntry<T> extends CollectionScopedEntry implements Comparable<T> {
    private T value;
    private Set<String> ids;

    public FieldIndexEntry(String databaseName, String collectionName, T value, Set<String> ids) {
        super(databaseName, collectionName);
        this.value = value;
        this.ids = ids;
    }

    public String toFileEntry() {
        return indexKeyOf(value) + Globals.ID_SEPARATOR + String.join(Globals.ID_SEPARATOR, ids);
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
        final var strValue = line.substring(0, separatorIdx);
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
        final var idsStr = line.substring(separatorIdx + Globals.ID_SEPARATOR.length());
        return new FieldIndexEntry<>(databaseName, collectionName, tClass.cast(value),
                Arrays.stream(idsStr.split(Globals.ID_SEPARATOR)).collect(Collectors.toSet()));
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
                    yield ownValue.compare(toCompareValue.getCustomValue());
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
