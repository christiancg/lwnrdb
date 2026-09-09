package org.techhouse.cache;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.utils.JsonUtils;
import org.techhouse.utils.SearchUtils;

// Maps one field operator plus its operand type onto the index that can answer it. A null result means
// no index can serve the operator, which is the caller's signal to fall back to a scan.
final class IndexLookupResolver {
    private IndexLookupResolver() {
    }

    @SuppressWarnings("unchecked")
    static <T> Set<String> doGetIdsFromIndex(UserCache userCache, String dbName, String collName, String fieldName,
            FieldOperator operator, T value) throws IOException {
        return switch (value) {
            case Number n -> {
                final var numberIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        Number.class);
                if (numberIndex != null) {
                    yield SearchUtils.findingByOperator(numberIndex, operator.getFieldOperatorType(), n);
                } else {
                    yield null;
                }
            }
            case Boolean b -> {
                final var booleanIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        Boolean.class);
                if (booleanIndex != null) {
                    yield SearchUtils.findingByOperator(booleanIndex, operator.getFieldOperatorType(), b);
                } else {
                    yield null;
                }
            }
            case String s -> {
                final var stringIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        String.class);
                if (stringIndex != null) {
                    yield SearchUtils.findingByOperator(stringIndex, operator.getFieldOperatorType(), s);
                } else {
                    yield null;
                }
            }
            case JsonCustom<?> c -> {
                final var customTypes = CustomTypeFactory.getCustomTypes();
                final var customClass = customTypes.get(c.getCustomTypeName());
                final var customIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                        (Class<T>) customClass);
                if (customIndex != null) {
                    yield SearchUtils.findingByOperator(customIndex, operator.getFieldOperatorType(), (T) c);
                } else {
                    yield null;
                }
            }
            case JsonObject obj -> {
                final var opType = operator.getFieldOperatorType();
                if (opType == FieldOperatorType.EQUALS || opType == FieldOperatorType.NOT_EQUALS) {
                    final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName,
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
                    // EQUALS/NOT_EQUALS against an array operand means element-match on the whole array.
                    case EQUALS, NOT_EQUALS -> {
                        final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName,
                                IndexKind.ARRAY);
                        yield hashIndex != null
                                ? SearchUtils.findingByOperator(hashIndex, opType, JsonUtils.hashElement(arr))
                                : null;
                    }
                    // IN/NOT_IN against an array operand means membership in the list of candidate values.
                    case IN, NOT_IN -> getIdsFromInList(userCache, dbName, collName, fieldName, operator, arr);
                    default -> null;
                };
            }
            default -> throw new IllegalStateException("Unexpected value: " + value);
        };
    }

    // The candidate list is assumed homogeneous: the index is chosen off its first element.
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
            final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName,
                    IndexKind.OBJECT);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonArray()) {
            final var hashIndex = userCache.getHashIndexAndLoadIfNecessary(dbName, collName, fieldName,
                    IndexKind.ARRAY);
            return hashIndex != null
                    ? SearchUtils.findingInNotIn(hashIndex, opType, listStream.map(JsonUtils::hashElement).toList())
                    : null;
        } else if (firstElement.isJsonPrimitive()) {
            final var prim = firstElement.asJsonPrimitive();
            return switch (prim) {
                case JsonCustom<?> c -> {
                    final var customClass = CustomTypeFactory.getCustomTypes().get(c.getCustomTypeName());
                    final var customIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                            (Class<T>) customClass);
                    yield customIndex != null
                            ? SearchUtils.findingInNotIn(customIndex, opType,
                                    (List<T>) listStream.map(JsonBaseElement::asJsonCustom).toList())
                            : null;
                }
                case JsonString ignored -> {
                    final var stringIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                            String.class);
                    yield stringIndex != null
                            ? SearchUtils.findingInNotIn(stringIndex, opType,
                                    listStream.map(x -> x.asJsonString().getValue()).toList())
                            : null;
                }
                case JsonNumber ignored -> {
                    final var numberIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
                            Number.class);
                    yield numberIndex != null
                            ? SearchUtils.findingInNotIn(numberIndex, opType,
                                    listStream.map(x -> x.asJsonNumber().getValue()).toList())
                            : null;
                }
                case JsonBoolean ignored -> {
                    final var booleanIndex = userCache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName,
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
