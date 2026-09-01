package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Map;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.TypeErrorException;

public final class EJsonInterop {
    private static final BigInteger MAX_EXACT_INTEGER = BigInteger.valueOf(9007199254740991L);
    // Mirrors InterpreterOps.BYTES_PER_ELEMENT / STRING_BYTES_PER_CHAR: the same cost model as the
    // allocation budget, restated here because values/ does not depend on builtins/.
    private static final long BYTES_PER_ELEMENT = 32L;
    private static final long BYTES_PER_CHAR = 2L;
    private static final long BYTES_PER_NUMBER = 8L;
    private static final long BYTES_PER_LITERAL = 4L;

    private EJsonInterop() {
    }

    // The spec path, shared with JSON.stringify: a BigInt has no JSON representation and must throw.
    public static JsonBaseElement toEjson(JsValue value) {
        return convert(value, new Conversion(false, new IdentityHashMap<>()), "");
    }

    // The host path, used by the script result contract and by everything heading into the database:
    // a BigInt that is exactly representable becomes a number, anything larger throws naming the
    // property path, because the error fires at the boundary rather than where the value was produced.
    public static JsonBaseElement toHostEjson(JsValue value) {
        return convert(value, new Conversion(true, new IdentityHashMap<>()), "");
    }

    private record Conversion(boolean hostMode, Map<JsValue, Boolean> visited) {
    }

    private static JsonBaseElement convert(JsValue value, Conversion mode, String path) {
        return switch (value) {
            case JsNumber n -> new JsonNumber(n.getValue());
            case JsBigInt bigInt -> bigIntToEjson(bigInt, mode, path);
            case JsString s -> new JsonString(s.getValue());
            case JsBoolean b -> new JsonBoolean(b.getValue());
            case JsNull ignored -> JsonNull.INSTANCE;
            case JsUndefined ignored -> null;
            case JsFunction ignored -> null;
            case JsNativeFunction ignored -> null;
            case JsClass ignored -> null;
            case JsArray array -> arrayToEjson(array, mode, path);
            case JsObject wrapper when wrapper.getPrimitive() != null -> convert(wrapper.getPrimitive(), mode, path);
            case JsObject object -> objectToEjson(object, mode, path);
            case JsRegExp ignored -> new JsonObject();
            case JsDate date -> date.toISOString() == null ? JsonNull.INSTANCE : new JsonString(date.toISOString());
            case JsTemporalDuration duration -> new JsonString(duration.toString());
            case JsTemporalPlainTime time -> new JsonString(time.toString());
            case JsTemporalPlainDate date -> new JsonString(date.toString());
            case JsTemporalInstant instant -> new JsonString(instant.toString());
            case JsTemporalPlainYearMonth yearMonth -> new JsonString(yearMonth.toString());
            case JsTemporalPlainMonthDay monthDay -> new JsonString(monthDay.toString());
            case JsTemporalPlainDateTime dt -> new JsonString(dt.toString());
            case JsTemporalZonedDateTime zdt -> new JsonString(zdt.toString());
            // The four EJson custom types cross back as their real JsonCustom instance, so EJson emits
            // "#geo(...)" and the storage/index layers see the type they already handle.
            case JsGeo geo -> geo.toJsonGeo();
            case JsVector vector -> vector.toJsonVector();
            case JsDbDateTime dateTime -> dateTime.toJsonDateTime();
            case JsDbTime time -> time.toJsonTime();
            case JsMap ignored -> new JsonObject();
            case JsSet ignored -> new JsonObject();
            case JsTypedArray typed -> typedArrayToEjson(typed, mode, path);
            case JsArrayBuffer ignored -> new JsonObject();
            case JsDataView ignored -> new JsonObject();
            case JsProxy proxy -> convert(proxy.getTarget(), mode, path);
            default -> null;
        };
    }

    private static JsonBaseElement bigIntToEjson(JsBigInt value, Conversion mode, String path) {
        if (!mode.hostMode()) {
            throw new TypeErrorException("Do not know how to serialize a BigInt");
        }
        if (value.getValue().abs().compareTo(MAX_EXACT_INTEGER) > 0) {
            throw new TypeErrorException(
                    "Cannot serialize BigInt" + at(path) + ": value exceeds the exact integer range");
        }
        return new JsonNumber(value.getValue().doubleValue());
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : " at '" + path + "'";
    }

    private static String memberPath(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String indexPath(String path, int index) {
        return path + "[" + index + "]";
    }

    private static JsonBaseElement typedArrayToEjson(JsTypedArray typed, Conversion mode, String path) {
        final var result = new JsonArray();
        for (var i = 0; i < typed.length(); i++) {
            final var converted = convert(typed.getElement(i), mode, indexPath(path, i));
            result.add(converted == null ? JsonNull.INSTANCE : converted);
        }
        return result;
    }

    private static JsonBaseElement arrayToEjson(JsArray array, Conversion mode, String path) {
        guardCycle(array, mode.visited());
        final var result = new JsonArray();
        final var elements = array.getElements();
        for (var i = 0; i < elements.size(); i++) {
            final var converted = convert(elements.get(i), mode, indexPath(path, i));
            result.add(converted == null ? JsonNull.INSTANCE : converted);
        }
        mode.visited().remove(array);
        return result;
    }

    private static JsonBaseElement objectToEjson(JsObject object, Conversion mode, String path) {
        guardCycle(object, mode.visited());
        final var result = new JsonObject();
        for (final var key : object.keys()) {
            if (!object.isEnumerable(key)) {
                continue;
            }
            final var converted = convert(object.get(key), mode, memberPath(path, key));
            if (converted != null) {
                result.add(key, converted);
            }
        }
        mode.visited().remove(object);
        return result;
    }

    private static void guardCycle(JsValue value, Map<JsValue, Boolean> visited) {
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new TypeErrorException("Converting circular structure to JSON");
        }
    }

    // What a converted value costs, used both by the script result cap and by the charging the host
    // boundary does before it materialises a database result as JS values.
    public static long estimatedBytes(JsonBaseElement element) {
        if (element == null) {
            return 0;
        }
        if (element instanceof JsonCustom<?> custom) {
            return stringBytes(custom.getValue());
        }
        return switch (element.getJsonType()) {
            case NUMBER -> BYTES_PER_NUMBER;
            case BOOLEAN, NULL -> BYTES_PER_LITERAL;
            case STRING -> stringBytes(element.asJsonString().getValue());
            case ARRAY -> arrayBytes(element.asJsonArray());
            case OBJECT -> objectBytes(element.asJsonObject());
            default -> BYTES_PER_ELEMENT;
        };
    }

    private static long stringBytes(String value) {
        return BYTES_PER_ELEMENT + (value == null ? 0 : value.length() * BYTES_PER_CHAR);
    }

    private static long arrayBytes(JsonArray array) {
        var total = BYTES_PER_ELEMENT;
        for (final var element : array) {
            total += estimatedBytes(element);
        }
        return total;
    }

    private static long objectBytes(JsonObject object) {
        var total = BYTES_PER_ELEMENT;
        for (final var entry : object.entrySet()) {
            total += entry.getKey().length() * BYTES_PER_CHAR + estimatedBytes(entry.getValue());
        }
        return total;
    }

    public static JsValue fromEjson(JsonBaseElement element) {
        if (element == null) {
            return JsNull.getInstance();
        }
        // JsonCustom extends JsonString, so getJsonType() answers STRING for it and the CUSTOM arm
        // below is unreachable: the custom types have to be recognised by their Java type instead.
        if (element instanceof JsonCustom<?> custom) {
            return customFromEjson(custom);
        }
        return switch (element.getJsonType()) {
            case NUMBER -> new JsNumber(element.asJsonNumber().getValue().doubleValue());
            case STRING -> new JsString(element.asJsonString().getValue());
            case BOOLEAN -> JsBoolean.of(element.asJsonBoolean().getValue());
            case NULL -> JsNull.getInstance();
            case ARRAY -> arrayFromEjson(element.asJsonArray());
            case OBJECT -> objectFromEjson(element.asJsonObject());
            default -> JsUndefined.getInstance();
        };
    }

    // A custom type registered later than these four degrades to its wire text rather than silently
    // vanishing as undefined, which is the bug the CUSTOM arm exists to fix.
    private static JsValue customFromEjson(JsonCustom<?> element) {
        return switch (element.getCustomTypeName()) {
            case JsonGeo.CUSTOM_TYPE_NAME -> new JsGeo(((JsonGeo) element).point());
            case JsonVector.CUSTOM_TYPE_NAME -> new JsVector(((JsonVector) element).getCustomValue());
            case JsonDateTime.CUSTOM_TYPE_NAME -> new JsDbDateTime(((JsonDateTime) element).getCustomValue());
            case JsonTime.CUSTOM_TYPE_NAME -> new JsDbTime(((JsonTime) element).getCustomValue());
            default -> new JsString(element.getValue());
        };
    }

    private static JsValue arrayFromEjson(JsonArray array) {
        final var result = new JsArray();
        for (final var element : array) {
            result.push(fromEjson(element));
        }
        return result;
    }

    private static JsValue objectFromEjson(JsonObject object) {
        final var result = new JsObject();
        for (final var entry : object.entrySet()) {
            result.set(entry.getKey(), fromEjson(entry.getValue()));
        }
        return result;
    }
}
