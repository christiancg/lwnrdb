package org.techhouse.cache;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.RangeBounds;
import org.techhouse.utils.SearchUtils;

final class IndexLookupResolver {
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);
    private static final ResourceLocking locking = IocContainer.get(ResourceLocking.class);

    private IndexLookupResolver() {
    }

    static <T> Set<String> doGetIdsFromIndex(UserCache userCache, String dbName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        final var opType = operator.getFieldOperatorType();
        return switch (value) {
            case JsonObject obj -> isEquality(opType)
                    ? hashLookup(userCache, dbName, collName, fieldName, opType, IndexKind.OBJECT, obj)
                    : null;
            case JsonArray arr -> switch (opType) {
                case EQUALS, NOT_EQUALS ->
                    hashLookup(userCache, dbName, collName, fieldName, opType, IndexKind.ARRAY, arr);
                case IN, NOT_IN ->
                    isHeterogeneous(arr) ? null : getIdsFromInList(userCache, dbName, collName, fieldName, opType, arr);
                default -> null;
            };
            default -> scalarLookup(userCache, dbName, collName, fieldName, opType, value);
        };
    }

    static Set<String> idsInRange(UserCache userCache, String dbName, String collName, RangeBounds bounds)
            throws IOException {
        final var fieldName = bounds.fieldName();
        return readingIndex(dbName, collName, fieldName, () -> {
            final var index = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
            if (index == null) {
                return null;
            }
            final var ids = SearchUtils.findingBetween(index, bounds);
            userCache.recordFieldIndexAccess(dbName, collName, fieldName);
            return new HashSet<>(ids);
        });
    }

    static OptionalLong countInRange(UserCache userCache, String dbName, String collName, RangeBounds bounds)
            throws IOException {
        final var fieldName = bounds.fieldName();
        return readingIndex(dbName, collName, fieldName, () -> {
            final var index = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class);
            if (index == null) {
                return OptionalLong.empty();
            }
            userCache.recordFieldIndexAccess(dbName, collName, fieldName);
            return OptionalLong.of(SearchUtils.countBetween(index, bounds));
        });
    }

    @FunctionalInterface
    private interface IndexRead<R> {
        R read() throws IOException;
    }

    private static <R> R readingIndex(String dbName, String collName, String fieldName, IndexRead<R> read)
            throws IOException {
        try {
            locking.lockIndexRead(dbName, collName, fieldName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring index read lock", e);
        }
        try {
            return read.read();
        } finally {
            locking.releaseIndexRead(dbName, collName, fieldName);
        }
    }

    private static boolean isEquality(FieldOperatorType opType) {
        return opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS;
    }

    private static Set<String> hashLookup(UserCache userCache, String dbName, String collName, String fieldName,
            FieldOperatorType opType, IndexKind kind, JsonBaseElement value) throws IOException {
        final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType, kind);
        return hashIndex != null
                ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(value))
                : null;
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<String> scalarLookup(UserCache userCache, String dbName, String collName, String fieldName,
            FieldOperatorType opType, T value) throws IOException {
        final var index = complementSafeIndex(userCache, dbName, collName, fieldName, opType,
                (Class<T>) scalarIndexClass(value));
        if (index == null || (opType == FieldOperatorType.CONTAINS && value instanceof String
                && hasAnotherIndex(userCache, dbName, collName, fieldName, String.class, null))) {
            return null;
        }
        return SearchUtils.findingByOperator(index, opType, value);
    }

    private static Class<?> scalarIndexClass(Object value) {
        return switch (value) {
            case Number _ -> Number.class;
            case Boolean _ -> Boolean.class;
            case String _ -> String.class;
            case JsonCustom<?> custom -> CustomTypeFactory.getCustomTypes().get(custom.getCustomTypeName());
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    private static boolean isHeterogeneous(JsonArray arr) {
        if (arr.isEmpty()) {
            return false;
        }
        final var first = kindOf(arr.get(0));
        for (final var element : arr.asList()) {
            if (!first.equals(kindOf(element))) {
                return true;
            }
        }
        return false;
    }

    private static String kindOf(JsonBaseElement element) {
        if (element.isJsonObject()) {
            return "object";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            if (primitive instanceof JsonCustom<?> custom) {
                return "custom:" + custom.getCustomTypeName();
            }
            if (primitive instanceof JsonString) {
                return "string";
            }
            if (primitive instanceof JsonNumber) {
                return "number";
            }
            if (primitive instanceof JsonBoolean) {
                return "boolean";
            }
        }
        return "other";
    }

    private static boolean hasAnotherIndex(UserCache userCache, String dbName, String collName, String fieldName,
            Class<?> chosenType, IndexKind chosenKind) throws IOException {
        for (final var type : IndexKind.scalarIndexClasses()) {
            if (type != chosenType
                    && userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type) != null) {
                return true;
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            if (kind != chosenKind
                    && userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean complementLacksUniverse(UserCache userCache, String dbName, String collName,
            String fieldName, Class<?> chosenType, IndexKind chosenKind) throws IOException {
        if (hasAnotherIndex(userCache, dbName, collName, fieldName, chosenType, chosenKind)) {
            return true;
        }
        final var indexed = indexedIds(userCache, dbName, collName, fieldName);
        final var pending = pendingIndexWrites.idsFor(dbName, collName);
        for (final var pkEntry : userCache.getPkIndexAndLoadIfNecessary(dbName, collName)) {
            final var id = pkEntry.getValue();
            if (!pending.contains(id) && !indexed.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> indexedIds(UserCache userCache, String dbName, String collName, String fieldName)
            throws IOException {
        final var covered = new HashSet<String>();
        for (final var type : IndexKind.scalarIndexClasses()) {
            addIds(covered, userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type));
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            addIds(covered, userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind));
        }
        return covered;
    }

    private static <T> void addIds(Set<String> covered, List<FieldIndexEntry<T>> index) {
        if (index != null) {
            for (final var entry : index) {
                covered.addAll(entry.getIds());
            }
        }
    }

    private static boolean isComplement(FieldOperatorType opType) {
        return opType == FieldOperatorType.NOT_IN || opType == FieldOperatorType.NOT_EQUALS;
    }

    private static <T> List<FieldIndexEntry<T>> complementSafeIndex(UserCache userCache, String dbName, String collName,
            String fieldName, FieldOperatorType opType, Class<T> chosen) throws IOException {
        if (isComplement(opType) && complementLacksUniverse(userCache, dbName, collName, fieldName, chosen, null)) {
            return null;
        }
        return userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, chosen);
    }

    private static List<FieldIndexEntry<String>> complementSafeHashIndex(UserCache userCache, String dbName,
            String collName, String fieldName, FieldOperatorType opType, IndexKind kind) throws IOException {
        if (isComplement(opType) && complementLacksUniverse(userCache, dbName, collName, fieldName, null, kind)) {
            return null;
        }
        return userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName, kind);
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<String> getIdsFromInList(UserCache userCache, String dbName, String collName,
            String fieldName, FieldOperatorType opType, JsonArray arr) throws IOException {
        if (arr.isEmpty()) {
            return null;
        }
        final var first = arr.get(0);
        if (first.isJsonObject() || first.isJsonArray()) {
            final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType,
                    first.isJsonObject() ? IndexKind.OBJECT : IndexKind.ARRAY);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType,
                            arr.asList().stream().map(JsonUtils::hashElement).toList())
                    : null;
        }
        final var firstValue = indexedValue(first);
        if (firstValue == null) {
            return null;
        }
        final var index = complementSafeIndex(userCache, dbName, collName, fieldName, opType,
                (Class<T>) scalarIndexClass(firstValue));
        return index != null
                ? SearchUtils.findingInNotIn(index, opType,
                        (List<T>) arr.asList().stream().map(IndexLookupResolver::indexedValue).toList())
                : null;
    }

    private static Object indexedValue(JsonBaseElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        return switch (element.asJsonPrimitive()) {
            case JsonCustom<?> custom -> custom;
            case JsonString string -> string.getValue();
            case JsonNumber number -> number.getValue();
            case JsonBoolean bool -> bool.getValue();
            default -> null;
        };
    }
}
