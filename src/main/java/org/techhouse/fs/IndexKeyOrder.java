package org.techhouse.fs;

import java.nio.charset.StandardCharsets;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.custom_types.JsonTemporalCustom;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.utils.CaseFolding;

final class IndexKeyOrder {
    private final Class<?> type;

    private IndexKeyOrder(Class<?> type) {
        this.type = type;
    }

    static boolean supports(Class<?> type) {
        return type == Number.class || type == Boolean.class || type == String.class
                || JsonTemporalCustom.class.isAssignableFrom(type);
    }

    static boolean supportsRanges(Class<?> type) {
        return type == Number.class || JsonTemporalCustom.class.isAssignableFrom(type);
    }

    static IndexKeyOrder of(Class<?> type) {
        return new IndexKeyOrder(type);
    }

    Object parse(byte[] bytes, int from, int to) {
        try {
            return FieldIndexEntry.parseIndexValue(new String(bytes, from, to - from, StandardCharsets.UTF_8), type);
        } catch (RuntimeException e) {
            throw new IndexFileDeclined();
        }
    }

    boolean rejects(Object operand) {
        return !type.isInstance(operand) && (type != Number.class || !(operand instanceof Number));
    }

    @SuppressWarnings("unchecked")
    int compare(Object stored, Object operand) {
        return switch (stored) {
            case Number number -> FieldIndexEntry.compareIndexedNumbers(number, (Number) operand);
            case Boolean bool -> Boolean.compare(bool, (Boolean) operand);
            case String string -> CaseFolding.compare(string, (String) operand);
            case JsonCustom<?> custom -> ((JsonCustom<Object>) custom).compareToCustom((JsonCustom<Object>) operand);
            default -> throw new IndexFileDeclined();
        };
    }

    int fileOrder(Object previous, Object next) {
        final var primary = compare(previous, next);
        if (primary != 0) {
            return primary;
        }
        return switch (previous) {
            case String string -> string.compareTo((String) next);
            case JsonCustom<?> custom -> FieldIndexEntry.fileKeyOf(custom).compareTo(FieldIndexEntry.fileKeyOf(next));
            default -> 0;
        };
    }
}
