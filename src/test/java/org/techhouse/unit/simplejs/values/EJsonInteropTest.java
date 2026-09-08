package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
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
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsDbTime;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsVector;
import org.techhouse.utils.GeoPoint;

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

    // EJsonInterop is the host boundary and runs after the event loop has drained, so it reads data
    // properties only: an accessor-valued key is absent from the script result rather than null.
    @Test
    public void test_accessor_property_is_not_serialized() {
        final var object = new JsObject();
        object.set("a", new JsNumber(1));
        object.defineAccessor("x", new JsNativeFunction("get x", (_, _) -> new JsNumber(2)), null);
        final var converted = EJsonInterop.toEjson(object).asJsonObject();
        assertEquals(1, converted.get("a").asJsonNumber().asInteger());
        assertNull(converted.get("x"));
    }

    // A primitive wrapper serializes as its boxed primitive: a String wrapper reaching db.save must
    // not land in the document as its exotic per-code-unit index properties.
    @Test
    public void test_primitive_wrapper_serializes_as_its_primitive() {
        final var string = new JsObject();
        string.setPrimitive(new JsString("ab"));
        assertEquals("ab", EJsonInterop.toEjson(string).asJsonString().getValue());
        final var number = new JsObject();
        number.setPrimitive(new JsNumber(3));
        assertEquals(3, EJsonInterop.toEjson(number).asJsonNumber().asInteger());
        final var bool = new JsObject();
        bool.setPrimitive(JsBoolean.TRUE);
        assertTrue(EJsonInterop.toEjson(bool).asJsonBoolean().getValue());
    }

    // Each of the four custom types crosses into the engine as its own value type
    @Test
    public void test_from_ejson_of_every_custom_type() {
        assertInstanceOf(JsGeo.class, EJsonInterop.fromEjson(new JsonGeo("#geo(1,2)")));
        assertInstanceOf(JsVector.class, EJsonInterop.fromEjson(new JsonVector("#vector(1,2)")));
        assertInstanceOf(JsDbDateTime.class,
                EJsonInterop.fromEjson(new JsonDateTime("#datetime(2024-01-02T03:04:05)")));
        assertInstanceOf(JsDbTime.class, EJsonInterop.fromEjson(new JsonTime("#time(03:04:05)")));
        assertEquals(1, ((JsGeo) EJsonInterop.fromEjson(new JsonGeo("#geo(1,2)"))).getPoint().lat());
    }

    // A custom type this engine has no value type for degrades to its wire text, not undefined
    @Test
    public void test_from_ejson_of_an_unknown_custom_type() {
        final var unknown = EJsonInterop.fromEjson(new UnknownCustom(7));
        assertInstanceOf(JsString.class, unknown);
        assertEquals("#mystery(7)", ((JsString) unknown).getValue());
    }

    // Each custom type serializes back as a real JsonCustom whose text round-trips
    @Test
    public void test_to_ejson_of_every_custom_type_round_trips() {
        final var values = List.of(new JsGeo(new GeoPoint(1, 2)), new JsVector(new double[]{1, 2}),
                new JsDbDateTime(LocalDateTime.of(2024, 1, 2, 3, 4, 5)), new JsDbTime(LocalTime.of(3, 4, 5)));
        for (final var value : values) {
            final var converted = EJsonInterop.toEjson(value);
            assertInstanceOf(JsonCustom.class, converted);
            assertEquals(value.getClass(), EJsonInterop.fromEjson(converted).getClass());
            assertEquals(value.toString(), ((JsonCustom<?>) converted).getValue());
        }
    }

    // The spec path stays spec-shaped: JSON.stringify's shared entry point still rejects a BigInt
    @Test
    public void test_to_ejson_still_throws_on_a_big_int() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toEjson(new JsBigInt(BigInteger.ONE)));
    }

    // The host path converts a losslessly-representable BigInt to a number
    @Test
    public void test_to_host_ejson_converts_an_exact_big_int() {
        final var max = new JsBigInt(BigInteger.valueOf(9007199254740991L));
        assertEquals(9007199254740991L, EJsonInterop.toHostEjson(max).asJsonNumber().getValue().longValue());
        assertEquals(-1, EJsonInterop.toHostEjson(new JsBigInt(BigInteger.valueOf(-1))).asJsonNumber().asInteger());
    }

    // Beyond the exact integer range it throws instead of silently losing precision
    @Test
    public void test_to_host_ejson_rejects_an_inexact_big_int() {
        final var huge = new JsBigInt(BigInteger.TWO.pow(64));
        final var error = assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(huge));
        assertTrue(error.getMessage().contains("exceeds the exact integer range"));
    }

    // The error names the property path, so the failure points at the value rather than the boundary
    @Test
    public void test_to_host_ejson_reports_the_property_path() {
        final var inner = new JsArray();
        inner.push(new JsBigInt(BigInteger.TWO.pow(64)));
        final var middle = new JsObject();
        middle.set("b", inner);
        final var outer = new JsObject();
        outer.set("a", middle);
        final var error = assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(outer));
        assertTrue(error.getMessage().contains("'a.b[0]'"), error.getMessage());
    }

    // A custom type registered outside the four the engine knows about, for the fallback arm above
    private static final class UnknownCustom extends JsonCustom<Integer> {
        private UnknownCustom(int value) {
            super(value);
        }

        @Override
        public String getCustomTypeName() {
            return "mystery";
        }

        @Override
        protected Integer parse() {
            return Integer.parseInt(stringDataValue());
        }

        @Override
        public Integer compare(Integer another) {
            return getCustomValue().compareTo(another);
        }

        @Override
        public java.util.Set<String> customOperatorNames() {
            return java.util.Set.of();
        }

        @Override
        public boolean applyCustomOperator(String operatorName, java.util.Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException("no custom operators");
        }

        @Override
        public java.util.Set<String> customRankingOperatorNames() {
            return java.util.Set.of();
        }

        @Override
        public double applyCustomRankingOperator(String operatorName, java.util.Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException("no ranking operators");
        }
    }
}
