package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.techhouse.config.Globals;

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
        return value + Globals.INDEX_ENTRY_SEPARATOR + String.join(";", ids);
    }

    public static <T> FieldIndexEntry<T> fromIndexFileEntry(String databaseName, String collectionName, String line, Class<T> tClass) {
        final var parts = line.split(Globals.INDEX_ENTRY_SEPARATOR_REGEX);
        final var strValue = parts[0];
        Object value;
        if (tClass == Double.class) {
            value = Double.parseDouble(strValue);
        } else if (strValue.equalsIgnoreCase("true") || strValue.equalsIgnoreCase("false")) {
            value = Boolean.parseBoolean(strValue);
        } else {
            value = strValue;
        }
        return new FieldIndexEntry<>(databaseName, collectionName, (T) value, Arrays.stream(parts[1].split(";")).collect(Collectors.toSet()));
    }

    @Override
    public int compareTo(T otherIndexValue) {
        return switch (value) {
            case Double d -> d.compareTo((Double) otherIndexValue);
            case Boolean b -> b.compareTo((Boolean) otherIndexValue);
            case String s -> s.compareToIgnoreCase((String) otherIndexValue);
            case null -> 0;
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }
}
