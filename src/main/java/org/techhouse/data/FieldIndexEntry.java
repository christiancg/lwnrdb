package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.techhouse.config.Globals;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@AllArgsConstructor
public class FieldIndexEntry<T> implements Comparable<T> {
    private String databaseName;
    private String collectionName;
    private T value;
    private Set<String> ids;

    public String toFileEntry() {
        String strValue;
        if (value instanceof JsonCustom<?>) {
            strValue = ((JsonCustom<?>) value).getValue();
        } else {
            strValue = value.toString();
        }
        return strValue + Globals.INDEX_ENTRY_SEPARATOR + String.join(";", ids);
    }

    public static <T> FieldIndexEntry<T> fromIndexFileEntry(String databaseName, String collectionName, String line, Class<T> tClass) {
        final var parts = line.split(Globals.INDEX_ENTRY_SEPARATOR_REGEX);
        final var strValue = parts[0];
        Object value;
        if (tClass == Double.class) {
            value = Double.parseDouble(strValue);
        } else if (tClass == Boolean.class) {
            value = Boolean.parseBoolean(strValue);
        } else if (tClass == String.class) {
            value = strValue;
        } else {
            value = CustomTypeFactory.getCustomTypeInstance(strValue);
        }
        return new FieldIndexEntry<>(databaseName, collectionName, tClass.cast(value), Arrays.stream(parts[1].split(";")).collect(Collectors.toSet()));
    }

    @Override
    public int compareTo(T otherIndexValue) {
        return switch (value) {
            case Double d -> d.compareTo((Double) otherIndexValue);
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
}
