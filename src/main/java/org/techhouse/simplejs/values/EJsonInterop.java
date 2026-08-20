package org.techhouse.simplejs.values;

import java.util.IdentityHashMap;
import java.util.Map;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.TypeErrorException;

public final class EJsonInterop {
    private EJsonInterop() {
    }

    public static JsonBaseElement toEjson(JsValue value) {
        return toEjson(value, new IdentityHashMap<>());
    }

    private static JsonBaseElement toEjson(JsValue value, Map<JsValue, Boolean> visited) {
        return switch (value) {
            case JsNumber n -> new JsonNumber(n.getValue());
            case JsBigInt ignored -> throw new TypeErrorException("Do not know how to serialize a BigInt");
            case JsString s -> new JsonString(s.getValue());
            case JsBoolean b -> new JsonBoolean(b.getValue());
            case JsNull ignored -> JsonNull.INSTANCE;
            case JsUndefined ignored -> null;
            case JsFunction ignored -> null;
            case JsNativeFunction ignored -> null;
            case JsClass ignored -> null;
            case JsArray array -> arrayToEjson(array, visited);
            case JsObject wrapper when wrapper.getPrimitive() != null -> toEjson(wrapper.getPrimitive(), visited);
            case JsObject object -> objectToEjson(object, visited);
            case JsRegExp ignored -> new JsonObject();
            case JsDate date -> date.toISOString() == null ? JsonNull.INSTANCE : new JsonString(date.toISOString());
            case JsTemporalDuration duration -> new JsonString(duration.toString());
            case JsTemporalPlainTime time -> new JsonString(time.toString());
            case JsTemporalPlainDate date -> new JsonString(date.toString());
            case JsMap ignored -> new JsonObject();
            case JsSet ignored -> new JsonObject();
            case JsTypedArray typed -> typedArrayToEjson(typed, visited);
            case JsArrayBuffer ignored -> new JsonObject();
            case JsDataView ignored -> new JsonObject();
            case JsProxy proxy -> toEjson(proxy.getTarget(), visited);
            default -> null;
        };
    }

    private static JsonBaseElement typedArrayToEjson(JsTypedArray typed, Map<JsValue, Boolean> visited) {
        final var result = new JsonArray();
        for (var i = 0; i < typed.length(); i++) {
            final var converted = toEjson(typed.getElement(i), visited);
            result.add(converted == null ? JsonNull.INSTANCE : converted);
        }
        return result;
    }

    private static JsonBaseElement arrayToEjson(JsArray array, Map<JsValue, Boolean> visited) {
        guardCycle(array, visited);
        final var result = new JsonArray();
        for (final var element : array.getElements()) {
            final var converted = toEjson(element, visited);
            result.add(converted == null ? JsonNull.INSTANCE : converted);
        }
        visited.remove(array);
        return result;
    }

    private static JsonBaseElement objectToEjson(JsObject object, Map<JsValue, Boolean> visited) {
        guardCycle(object, visited);
        final var result = new JsonObject();
        for (final var key : object.keys()) {
            if (!object.isEnumerable(key)) {
                continue;
            }
            final var converted = toEjson(object.get(key), visited);
            if (converted != null) {
                result.add(key, converted);
            }
        }
        visited.remove(object);
        return result;
    }

    private static void guardCycle(JsValue value, Map<JsValue, Boolean> visited) {
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new TypeErrorException("Converting circular structure to JSON");
        }
    }

    public static JsValue fromEjson(JsonBaseElement element) {
        if (element == null) {
            return JsNull.getInstance();
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
