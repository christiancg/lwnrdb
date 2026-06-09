package org.techhouse.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
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
        return strValue + Globals.ID_SEPARATOR + String.join(Globals.ID_SEPARATOR, ids);
    }

    public static <T> FieldIndexEntry<T> fromIndexFileEntry(String databaseName, String collectionName, String line, Class<T> tClass) {
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
        return new FieldIndexEntry<>(databaseName, collectionName, tClass.cast(value), Arrays.stream(idsStr.split(Globals.ID_SEPARATOR)).collect(Collectors.toSet()));
    }

    @Override
    public int compareTo(@NonNull T otherIndexValue) {
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
}
