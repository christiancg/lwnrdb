package org.techhouse.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.bckg_ops.PendingIndexWrites;
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
import org.techhouse.utils.SearchUtils;

final class IndexLookupResolver {
    private static final List<IndexKind> HASH_INDEX_KINDS = List.of(IndexKind.OBJECT, IndexKind.ARRAY);
    private static final PendingIndexWrites pendingIndexWrites = IocContainer.get(PendingIndexWrites.class);

    private IndexLookupResolver() {
    }

    @SuppressWarnings("unchecked")
    static <T> Set<String> doGetIdsFromIndex(UserCache userCache, String dbName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        return switch (value) {
            case Number n -> {
                final var numberIndex = complementSafeIndex(userCache, dbName, collName, fieldName,
                        operator.getFieldOperatorType(), Number.class);
                if (numberIndex != null) {
                    yield SearchUtils.findingByOperator(numberIndex, operator.getFieldOperatorType(), n);
                } else {
                    yield null;
                }
            }
            case Boolean b -> {
                final var booleanIndex = complementSafeIndex(userCache, dbName, collName, fieldName,
                        operator.getFieldOperatorType(), Boolean.class);
                if (booleanIndex != null) {
                    yield SearchUtils.findingByOperator(booleanIndex, operator.getFieldOperatorType(), b);
                } else {
                    yield null;
                }
            }
            case String s -> {
                final var stringIndex = complementSafeIndex(userCache, dbName, collName, fieldName,
                        operator.getFieldOperatorType(), String.class);
                if (stringIndex == null) {
                    yield null;
                } else if (operator.getFieldOperatorType() == FieldOperatorType.CONTAINS
                        && hasAnotherIndex(userCache, dbName, collName, fieldName, String.class, null)) {
                    yield null;
                } else {
                    yield SearchUtils.findingByOperator(stringIndex, operator.getFieldOperatorType(), s);
                }
            }
            case JsonCustom<?> c -> {
                final var customTypes = CustomTypeFactory.getCustomTypes();
                final var customClass = customTypes.get(c.getCustomTypeName());
                final var customIndex = complementSafeIndex(userCache, dbName, collName, fieldName,
                        operator.getFieldOperatorType(), (Class<T>) customClass);
                if (customIndex != null) {
                    yield SearchUtils.findingByOperator(customIndex, operator.getFieldOperatorType(), (T) c);
                } else {
                    yield null;
                }
            }
            case JsonObject obj -> {
                final var opType = operator.getFieldOperatorType();
                if (opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS) {
                    final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType,
                            IndexKind.OBJECT);
                    yield hashIndex != null
                            ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(obj))
                            : null;
                } else {
                    yield null;
                }
            }
            case JsonArray arr -> {
                final var opType = operator.getFieldOperatorType();
                yield switch (opType) {
                    case EQUALS, NOT_EQUALS -> {
                        final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType,
                                IndexKind.ARRAY);
                        yield hashIndex != null
                                ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(arr))
                                : null;
                    }
                    case IN, NOT_IN -> isHeterogeneous(arr)
                            ? null
                            : getIdsFromInList(userCache, dbName, collName, fieldName, operator, arr);
                    default -> null;
                };
            }
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
        for (final var type : List.of(Number.class, Boolean.class, String.class)) {
            if (type != chosenType
                    && userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type) != null) {
                return true;
            }
        }
        for (final var customType : CustomTypeFactory.getCustomTypes().values()) {
            if (customType != chosenType
                    && userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, customType) != null) {
                return true;
            }
        }
        for (final var kind : HASH_INDEX_KINDS) {
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
        final var types = new ArrayList<Class<?>>(List.of(Number.class, Boolean.class, String.class));
        types.addAll(CustomTypeFactory.getCustomTypes().values());
        for (final var type : types) {
            addIds(covered, userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, type));
        }
        for (final var kind : HASH_INDEX_KINDS) {
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
            String fieldName, FieldOperator operator, JsonArray arr) throws IOException {
        if (arr.isEmpty()) {
            return null;
        }
        final var firstElement = arr.get(0);
        final var listStream = arr.asList().stream();
        final var opType = operator.getFieldOperatorType();
        if (firstElement.isJsonObject()) {
            final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType,
                    IndexKind.OBJECT);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonArray()) {
            final var hashIndex = complementSafeHashIndex(userCache, dbName, collName, fieldName, opType,
                    IndexKind.ARRAY);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonPrimitive()) {
            final var prim = firstElement.asJsonPrimitive();
            return switch (prim) {
                case JsonCustom<?> c -> {
                    final var customClass = CustomTypeFactory.getCustomTypes().get(c.getCustomTypeName());
                    final var customIndex = (List<FieldIndexEntry<T>>) (List<?>) complementSafeIndex(userCache, dbName,
                            collName, fieldName, opType, customClass);
                    yield customIndex != null
                            ? SearchUtils.findingInNotIn(customIndex, opType,
                                    (List<T>) listStream.map(JsonBaseElement::asJsonCustom).toList())
                            : null;
                }
                case JsonString ignored -> {
                    final var stringIndex = complementSafeIndex(userCache, dbName, collName, fieldName, opType,
                            String.class);
                    yield stringIndex != null
                            ? SearchUtils.findingInNotIn(stringIndex, opType,
                                    listStream.map(x -> x.asJsonString().getValue()).toList())
                            : null;
                }
                case JsonNumber ignored -> {
                    final var numberIndex = complementSafeIndex(userCache, dbName, collName, fieldName, opType,
                            Number.class);
                    yield numberIndex != null
                            ? SearchUtils.findingInNotIn(numberIndex, opType,
                                    listStream.map(x -> x.asJsonNumber().getValue()).toList())
                            : null;
                }
                case JsonBoolean ignored -> {
                    final var booleanIndex = complementSafeIndex(userCache, dbName, collName, fieldName, opType,
                            Boolean.class);
                    yield booleanIndex != null
                            ? SearchUtils.findingInNotIn(booleanIndex, opType,
                                    listStream.map(x -> x.asJsonBoolean().getValue()).toList())
                            : null;
                }
                default -> null;
            };
        }
        return null;
    }
}
