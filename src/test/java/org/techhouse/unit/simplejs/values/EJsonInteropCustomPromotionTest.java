package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsVector;
import org.techhouse.utils.GeoPoint;

public class EJsonInteropCustomPromotionTest {
    private static JsonBaseElement hostValueOf(String source) {
        return EJsonInterop.toHostEjson(new JsString(source));
    }

    @Test
    public void test_host_conversion_promotes_a_geo_string() {
        final var converted = hostValueOf("#geo(1.0,2.0)");
        assertTrue(converted.isJsonCustom());
        assertInstanceOf(JsonGeo.class, converted);
    }

    @Test
    public void test_host_conversion_promotes_datetime_time_and_vector() {
        assertInstanceOf(JsonDateTime.class, hostValueOf("#datetime(2024-01-15T10:30)"));
        assertInstanceOf(JsonTime.class, hostValueOf("#time(10:30)"));
        assertInstanceOf(JsonVector.class, hostValueOf("#vector(1.0,2.0,3.0)"));
    }

    @Test
    public void test_host_conversion_preserves_the_raw_wire_text() {
        assertEquals("#geo(1,2)", hostValueOf("#geo(1,2)").asJsonString().getValue());
        assertEquals("#time(10:30)", hostValueOf("#time(10:30)").asJsonString().getValue());
    }

    @Test
    public void test_host_conversion_leaves_an_ordinary_string_alone() {
        for (final var plain : List.of("#notacustom", "plain", "", "#", "#geo", "geo(1,2)")) {
            final var converted = hostValueOf(plain);
            assertFalse(converted.isJsonCustom(), plain + " must stay an ordinary string");
            assertEquals(plain, converted.asJsonString().getValue());
        }
    }

    @Test
    public void test_host_conversion_refuses_an_unregistered_custom_type() {
        final var error = assertThrows(TypeErrorException.class, () -> hostValueOf("#nosuch(1)"));
        assertTrue(error.getMessage().contains("nosuch"), error.getMessage());
    }

    @Test
    public void test_host_conversion_refuses_a_malformed_custom_value() {
        assertThrows(TypeErrorException.class, () -> hostValueOf("#geo(bad)"));
    }

    @Test
    public void test_a_refused_custom_value_names_the_member_path() {
        final var object = new JsObject();
        object.set("f", new JsString("#nosuch(1)"));

        final var error = assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(object));

        assertTrue(error.getMessage().contains("'f'"), error.getMessage());
    }

    @Test
    public void test_host_conversion_promotes_inside_arrays_and_sub_objects() {
        final var nested = new JsObject();
        nested.set("loc", new JsString("#geo(3.0,4.0)"));
        final var list = new JsArray();
        list.push(new JsString("#time(08:00)"));
        final var outer = new JsObject();
        outer.set("nested", nested);
        outer.set("list", list);

        final var converted = EJsonInterop.toHostEjson(outer).asJsonObject();

        assertInstanceOf(JsonGeo.class, converted.get("nested").asJsonObject().get("loc"));
        assertInstanceOf(JsonTime.class, converted.get("list").asJsonArray().get(0));
    }

    @Test
    public void test_host_conversion_never_promotes_a_key() {
        final var object = new JsObject();
        object.set("#geo(1.0,2.0)", new JsString("value"));

        final var converted = EJsonInterop.toHostEjson(object).asJsonObject();

        assertTrue(converted.has("#geo(1.0,2.0)"));
    }

    @Test
    public void test_stringify_conversion_does_not_promote() {
        final var converted = EJsonInterop.toEjson(new JsString("#geo(1,2)"));
        assertFalse(converted.isJsonCustom());
        assertEquals(JsonString.class, converted.getClass());
    }

    @Test
    public void test_promotion_matches_the_lexer() {
        for (final var wire : List.of("#geo(1,2)", "#geo(1.0,2.0)", "#time(10:30)", "#datetime(2024-01-15T10:30)",
                "#vector(1.0,2.0)", "#notacustom", "plain")) {
            final var document = new EJson().fromJson("{\"v\":\"" + wire + "\"}", JsonObject.class).get("v");
            final var promoted = hostValueOf(wire);
            assertEquals(document.getClass(), promoted.getClass(), wire + " must convert to the lexer's own type");
            assertEquals(document.asJsonString().getValue(), promoted.asJsonString().getValue());
            if (document instanceof JsonCustom<?> custom) {
                assertTrue(Objects.deepEquals(custom.getCustomValue(), ((JsonCustom<?>) promoted).getCustomValue()),
                        wire + " must parse to the same custom value the lexer builds");
            }
        }
    }

    private static String roundTrip(JsonBaseElement element) {
        return EJsonInterop.toHostEjson(EJsonInterop.fromEjson(element)).asJsonString().getValue();
    }

    @Test
    public void geoRoundTripKeepsIntegralSpelling() {
        assertEquals("#geo(45,-122)", roundTrip(new JsonGeo("#geo(45,-122)")));
    }

    @Test
    public void vectorRoundTripKeepsSpelling() {
        assertEquals("#vector(1,2,1e+20)", roundTrip(new JsonVector("#vector(1,2,1e+20)")));
    }

    @Test
    public void dateTimeRoundTripKeepsSeconds() {
        assertEquals("#datetime(2024-01-01T10:00:00)", roundTrip(new JsonDateTime("#datetime(2024-01-01T10:00:00)")));
    }

    @Test
    public void timeRoundTripKeepsSeconds() {
        assertEquals("#time(10:00:00)", roundTrip(new JsonTime("#time(10:00:00)")));
    }

    @Test
    public void roundTripReturnsSourceNestedInObjectAndArray() {
        final var array = new JsonArray();
        array.add(new JsonGeo("#geo(1,2)"));
        final var document = new JsonObject();
        document.add("at", new JsonTime("#time(10:00:00)"));
        document.add("points", array);

        final var converted = EJsonInterop.toHostEjson(EJsonInterop.fromEjson(document)).asJsonObject();

        assertEquals("#time(10:00:00)", converted.get("at").asJsonString().getValue());
        assertEquals("#geo(1,2)", converted.get("points").asJsonArray().get(0).asJsonString().getValue());
    }

    @Test
    public void scriptConstructedGeoKeepsDerivedSpelling() {
        assertEquals("#geo(45.0,-122.0)", new JsGeo(new GeoPoint(45, -122)).toJsonGeo().getValue());
        assertEquals("#vector(1.0,2.0)", new JsVector(new double[]{1, 2}).toJsonVector().getValue());
    }

    @Test
    public void toStringIsIndependentOfSource() {
        assertEquals(new JsGeo(new GeoPoint(45, -122)).toString(), new JsGeo(new JsonGeo("#geo(45,-122)")).toString());
        assertEquals(new JsDbDateTime(LocalDateTime.of(2024, 1, 1, 10, 0)).toString(),
                new JsDbDateTime(new JsonDateTime("#datetime(2024-01-01T10:00:00)")).toString());
    }

    @Test
    public void copyKeepsSource() {
        assertEquals("#geo(45,-122)", new JsGeo(new JsonGeo("#geo(45,-122)")).copy().toJsonGeo().getValue());
        assertEquals("#vector(1,2)", new JsVector(new JsonVector("#vector(1,2)")).copy().toJsonVector().getValue());
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN})
    public void test_host_conversion_refuses_a_non_finite_number(double value) {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(new JsNumber(value)));
    }

    @Test
    public void test_host_conversion_names_the_member_path() {
        final var inner = new JsObject();
        inner.set("b", new JsNumber(Double.POSITIVE_INFINITY));
        final var outer = new JsObject();
        outer.set("a", inner);

        final var error = assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(outer));

        assertTrue(error.getMessage().contains("a.b"), error.getMessage());
        assertTrue(error.getMessage().contains("Infinity"), error.getMessage());
    }

    @Test
    public void test_plain_conversion_keeps_negative_zero() {
        assertEquals(Double.doubleToRawLongBits(-0.0), Double
                .doubleToRawLongBits(EJsonInterop.toEjson(new JsNumber(-0.0)).asJsonNumber().getValue().doubleValue()));
    }

    @Test
    public void test_host_conversion_refuses_a_geo_string_with_a_non_finite_point() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(new JsString("#geo(NaN,1)")));
    }

    @Test
    public void test_host_conversion_accepts_every_finite_double() {
        assertEquals(Double.MAX_VALUE,
                EJsonInterop.toHostEjson(new JsNumber(Double.MAX_VALUE)).asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MIN_VALUE,
                EJsonInterop.toHostEjson(new JsNumber(Double.MIN_VALUE)).asJsonNumber().getValue().doubleValue());
        assertEquals(0.0, EJsonInterop.toHostEjson(new JsNumber(0.0)).asJsonNumber().getValue().doubleValue());
        assertEquals(0L,
                Double.doubleToRawLongBits(
                        EJsonInterop.toHostEjson(new JsNumber(-0.0)).asJsonNumber().getValue().doubleValue()),
                "a stored zero is positive, as it reads back after a restart");
    }

    @Test
    public void test_stringify_conversion_still_accepts_non_finite() {
        assertTrue(Double.isInfinite(
                EJsonInterop.toEjson(new JsNumber(Double.POSITIVE_INFINITY)).asJsonNumber().getValue().doubleValue()));
        assertTrue(
                Double.isNaN(EJsonInterop.toEjson(new JsNumber(Double.NaN)).asJsonNumber().getValue().doubleValue()));
    }
}
