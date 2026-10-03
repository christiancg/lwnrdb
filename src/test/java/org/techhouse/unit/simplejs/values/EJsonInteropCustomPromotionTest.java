package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;

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
}
