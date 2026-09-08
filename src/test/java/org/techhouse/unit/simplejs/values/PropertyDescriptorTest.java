package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;

public class PropertyDescriptorTest {
    private static List<String> stringKeys(JsValue target) {
        return target.ownPropertyKeys().stream().filter(JsString.class::isInstance)
                .map(key -> ((JsString) key).getValue()).toList();
    }

    private static PropertyDescriptor value(JsValue value) {
        return new PropertyDescriptor(value, null, null, true, true, true);
    }

    // The record's factories fill in the attribute triple and classify the descriptor
    @Test
    public void test_descriptor_factories() {
        final var data = PropertyDescriptor.data(new JsNumber(1), new PropertyFlags(true, false, true));
        assertFalse(data.isAccessorDescriptor());
        assertTrue(data.writableOr(false));
        assertFalse(data.enumerableOr(true));
        assertTrue(data.configurableOr(false));
        final var getter = new JsNativeFunction("g", (_, _) -> new JsNumber(2));
        final var accessor = PropertyDescriptor.accessor(getter, null, new PropertyFlags(false, true, false));
        assertTrue(accessor.isAccessorDescriptor());
        assertSame(getter, accessor.getter());
        assertInstanceOf(JsUndefined.class, accessor.setter());
        assertTrue(accessor.enumerableOr(false));
        assertFalse(accessor.configurableOr(true));
    }

    // An absent attribute falls back to the current one rather than to false
    @Test
    public void test_absent_attributes_keep_the_current_value() {
        final var partial = new PropertyDescriptor(new JsNumber(1), null, null, null, null, null);
        assertTrue(partial.writableOr(true));
        assertTrue(partial.enumerableOr(true));
        assertTrue(partial.configurableOr(true));
    }

    // A primitive has no property table, so it owns nothing and accepts no definition
    @Test
    public void test_primitive_owns_nothing() {
        final var primitive = new JsString("abc");
        assertEquals(List.of(), primitive.ownPropertyKeys());
        assertNull(primitive.getOwnProperty(new JsString("length")));
        assertFalse(primitive.hasOwnKey(new JsString("length")));
        assertFalse(primitive.defineOwnProperty(new JsString("x"), value(new JsNumber(1))));
        assertTrue(primitive.deleteOwnProperty(new JsString("x")));
    }

    // An ordinary object answers all five operations from its table, symbol keys included
    @Test
    public void test_ordinary_object_protocol() {
        final var object = new JsObject();
        final var symbol = new JsSymbol("tag");
        assertTrue(object.defineOwnProperty(new JsString("a"), value(new JsNumber(1))));
        assertTrue(object.defineOwnProperty(symbol, value(new JsNumber(2))));
        assertEquals(List.of("a"), stringKeys(object));
        assertTrue(object.ownPropertyKeys().contains(symbol));
        final var descriptor = object.getOwnProperty(new JsString("a"));
        assertNotNull(descriptor);
        assertEquals(1, ((JsNumber) descriptor.value()).getValue());
        assertTrue(object.hasOwnKey(symbol));
        assertTrue(object.deleteOwnProperty(new JsString("a")));
        assertTrue(object.deleteOwnProperty(symbol));
        assertFalse(object.hasOwnKey(symbol));
    }

    // An accessor definition is reported back as an accessor descriptor
    @Test
    public void test_accessor_definition_round_trip() {
        final var object = new JsObject();
        final var getter = new JsNativeFunction("g", (_, _) -> new JsNumber(3));
        object.defineOwnProperty(new JsString("a"), new PropertyDescriptor(null, getter, null, null, true, true));
        final var descriptor = object.getOwnProperty(new JsString("a"));
        assertNotNull(descriptor);
        assertTrue(descriptor.isAccessorDescriptor());
        assertSame(getter, descriptor.getter());
    }

    // Redefining a non-configurable property is rejected with a TypeError naming the key
    @Test
    public void test_non_configurable_redefine_is_rejected() {
        final var object = new JsObject();
        object.defineOwnProperty(new JsString("a"),
                new PropertyDescriptor(new JsNumber(1), null, null, false, false, false));
        final var descriptor = new PropertyDescriptor(new JsNumber(2), null, null, null, null, null);
        final var error = assertThrows(TypeErrorException.class,
                () -> object.defineOwnProperty(new JsString("a"), descriptor));
        assertEquals("Cannot redefine property: a", error.getMessage());
        assertFalse(object.deleteOwnProperty(new JsString("a")));
    }

    // A non-extensible object refuses a brand new key
    @Test
    public void test_new_key_on_non_extensible_object() {
        final var object = new JsObject();
        object.preventExtensions();
        final var descriptor = value(new JsNumber(1));
        assertThrows(TypeErrorException.class, () -> object.defineOwnProperty(new JsString("a"), descriptor));
    }

    // An array reports its indices, then length (created with the array), then its named properties
    @Test
    public void test_array_own_keys_and_descriptors() {
        final var array = new JsArray(List.of(new JsNumber(1)));
        array.pushHole();
        array.defineOwnProperty(new JsString("tag"), value(new JsString("t")));
        assertEquals(List.of("0", "length", "tag"), stringKeys(array));
        final var length = array.getOwnProperty(new JsString("length"));
        assertNotNull(length);
        assertEquals(2, ((JsNumber) length.value()).getValue());
        assertFalse(length.enumerableOr(true));
        assertNull(array.getOwnProperty(new JsString("1")));
        assertNotNull(array.getOwnProperty(new JsString("0")));
    }

    // Defining an index writes through the element storage; deleting one leaves a hole
    @Test
    public void test_array_index_definition_and_delete() {
        final var array = new JsArray();
        assertTrue(array.defineOwnProperty(new JsString("0"), value(new JsNumber(7))));
        assertEquals(7, ((JsNumber) array.get(0)).getValue());
        array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(3), null, null, null, null, null));
        assertEquals(3, array.length());
        assertTrue(array.deleteOwnProperty(new JsString("0")));
        assertTrue(array.isHole(0));
        assertTrue(array.deleteOwnProperty(new JsString("missing")));
    }

    // A non-writable length rejects a resize, and an accessor length is never legal
    @Test
    public void test_array_length_is_exotic() {
        final var array = new JsArray(List.of(new JsNumber(1)));
        array.setLengthWritable(false);
        final var resize = new PropertyDescriptor(new JsNumber(4), null, null, null, null, null);
        assertThrows(TypeErrorException.class, () -> array.defineOwnProperty(new JsString("length"), resize));
        final var accessor = new PropertyDescriptor(null, new JsNativeFunction("g", (_, _) -> new JsNumber(0)), null,
                null, null, null);
        assertThrows(TypeErrorException.class, () -> array.defineOwnProperty(new JsString("length"), accessor));
    }

    // The global object's string keys live in the Environment, so a definition writes the binding
    @Test
    public void test_global_object_protocol() {
        final var env = Environment.global();
        final var global = new JsGlobalObject(env);
        env.setGlobal("gx", new JsNumber(1));
        assertTrue(stringKeys(global).contains("gx"));
        final var descriptor = global.getOwnProperty(new JsString("gx"));
        assertNotNull(descriptor);
        assertEquals(1, ((JsNumber) descriptor.value()).getValue());
        assertTrue(global.defineOwnProperty(new JsString("gx"), value(new JsNumber(2))));
        assertEquals(2, ((JsNumber) env.get("gx")).getValue());
        assertTrue(global.deleteOwnProperty(new JsString("gx")));
        assertFalse(env.isDeclared("gx"));
        assertNull(global.getOwnProperty(new JsString("gx")));
    }

    // A callable's synthesised name/length are real own properties once reflected upon
    @Test
    public void test_callable_metadata_is_materialised() {
        final var function = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        final var name = function.getOwnProperty(new JsString("name"));
        assertNotNull(name);
        assertEquals("f", ((JsString) name.value()).getValue());
        assertFalse(name.writableOr(true));
        assertTrue(name.configurableOr(false));
        assertTrue(stringKeys(function).contains("length"));
        function.defineOwnProperty(new JsString("name"), value(new JsString("g")));
        final var redefined = function.getOwnProperty(new JsString("name"));
        assertNotNull(redefined);
        assertEquals("g", ((JsString) redefined.value()).getValue());
    }
}
