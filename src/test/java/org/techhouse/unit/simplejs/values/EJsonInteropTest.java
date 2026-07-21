package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class EJsonInteropTest {
    // Scalars convert to their EJson counterparts
    @Test
    public void test_scalars_to_ejson() {
        assertInstanceOf(JsonNumber.class, EJsonInterop.toEjson(new JsNumber(3)));
        assertInstanceOf(JsonString.class, EJsonInterop.toEjson(new JsString("x")));
        assertInstanceOf(JsonBoolean.class, EJsonInterop.toEjson(JsBoolean.TRUE));
        assertInstanceOf(JsonNull.class, EJsonInterop.toEjson(JsNull.getInstance()));
    }

    // undefined and functions convert to null (dropped by callers)
    @Test
    public void test_undefined_and_function_to_ejson() {
        assertNull(EJsonInterop.toEjson(JsUndefined.getInstance()));
        assertNull(EJsonInterop.toEjson(new JsNativeFunction("f", (_, _) -> JsNull.getInstance())));
    }

    // a BigInt cannot be converted
    @Test
    public void test_bigint_to_ejson_throws() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toEjson(new JsBigInt(BigInteger.ONE)));
    }

    // objects and arrays round-trip, dropping undefined members
    @Test
    public void test_object_array_roundtrip() {
        final var object = new JsObject();
        object.set("a", new JsNumber(1));
        object.set("skip", JsUndefined.getInstance());
        object.set("nested", new JsArray(List.of(new JsNumber(2), new JsString("y"))));
        final var ejson = (JsonObject) EJsonInterop.toEjson(object);
        assertEquals(2, ejson.size());
        final var back = (JsObject) EJsonInterop.fromEjson(ejson);
        assertEquals(1, ((JsNumber) back.get("a")).getValue());
        assertInstanceOf(JsArray.class, back.get("nested"));
    }

    // array holes of undefined become null on the EJson side
    @Test
    public void test_array_undefined_to_null() {
        final var array = new JsArray(List.of(new JsNumber(1), JsUndefined.getInstance()));
        final var ejson = (JsonArray) EJsonInterop.toEjson(array);
        assertInstanceOf(JsonNull.class, ejson.get(1));
    }

    // fromEjson maps a null element to JS null
    @Test
    public void test_fromejson_null() {
        assertInstanceOf(JsNull.class, EJsonInterop.fromEjson(null));
        assertInstanceOf(JsNull.class, EJsonInterop.fromEjson(JsonNull.INSTANCE));
    }

    // a circular structure is rejected
    @Test
    public void test_circular_throws() {
        final var object = new JsObject();
        object.set("self", object);
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toEjson(object));
    }

    // a regex serializes to an empty object, matching JSON.stringify(/x/)
    @Test
    public void test_regex_to_empty_object() {
        final var result = EJsonInterop.toEjson(RegexTranslator.compile("x", "g"));
        assertInstanceOf(JsonObject.class, result);
        assertTrue(result.asJsonObject().entrySet().isEmpty());
    }
}
