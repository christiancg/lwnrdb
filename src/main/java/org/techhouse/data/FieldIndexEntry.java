package org.techhouse.data;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;

public class FieldIndexEntry<T> implements Comparable<T> {
    private String databaseName;
    private String collectionName;
    private T value;
    private Set<String> ids;

    public FieldIndexEntry(String databaseName, String collectionName, T value, Set<String> ids) {
        this.databaseName = databaseName;
        this.collectionName = collectionName;
        this.value = value;
        this.ids = ids;
    }

    public String toFileEntry() {
        String strValue;
        if (value instanceof JsonCustom<?>) {
            strValue = ((JsonCustom<?>) value).getValue();
        } else {
            strValue = value.toString();
        }
        return strValue + Globals.ID_SEPARATOR + String.join(Globals.ID_SEPARATOR, ids);
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

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
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
    public int compareTo(T otherIndexValue) {
        Objects.requireNonNull(otherIndexValue);
        return switch (value) {
            case Number d -> Double.compare(d.doubleValue(), ((Number) otherIndexValue).doubleValue());
            case Boolean b -> b.compareTo((Boolean) otherIndexValue);
            case String s -> s.compareToIgnoreCase((String) otherIndexValue);
            case null -> 0;
            default -> {
                if (otherIndexValue instanceof JsonCustom) {
                    final var ownValue = (JsonCustom<T>) value;
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
