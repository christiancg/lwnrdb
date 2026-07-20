package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class JsValueTest {
    // Each concrete value reports its own type, driving the internalGetType switch
    @Test
    public void test_get_type_for_each_value() {
        assertEquals(JsValue.JsValueType.NUMBER, new JsNumber(1).getType());
        assertEquals(JsValue.JsValueType.STRING, new JsString("a").getType());
        assertEquals(JsValue.JsValueType.BOOLEAN, JsBoolean.TRUE.getType());
        assertEquals(JsValue.JsValueType.BIGINT, new JsBigInt(BigInteger.ONE).getType());
        assertEquals(JsValue.JsValueType.UNDEFINED, JsUndefined.getInstance().getType());
        assertEquals(JsValue.JsValueType.NULL, JsNull.getInstance().getType());
        assertEquals(JsValue.JsValueType.OBJECT, new JsObject().getType());
        assertEquals(JsValue.JsValueType.ARRAY, new JsArray().getType());
        assertEquals(JsValue.JsValueType.FUNCTION,
                new JsFunction(null, List.of(), null, false, false, Environment.global()).getType());
        assertEquals(JsValue.JsValueType.FUNCTION,
                new JsNativeFunction("id", (_, _) -> JsUndefined.getInstance()).getType());
        assertEquals(JsValue.JsValueType.CLASS, new JsClass("A", null, Environment.global()).getType());
    }

    // A class value is typeof "function" and stringifies with its name
    @Test
    public void test_class_value() {
        final var cls = new JsClass("Widget", null, Environment.global());
        assertEquals("function", JsCoercion.typeOf(cls));
        assertEquals("class Widget", JsCoercion.toStr(cls));
        assertEquals("Widget", cls.getName());
    }

    // A class instance links to its class and stores private fields
    @Test
    public void test_object_class_and_private_fields() {
        final var cls = new JsClass("A", null, Environment.global());
        final var object = new JsObject();
        object.setKlass(cls);
        assertSame(cls, object.getKlass());
        assertFalse(object.hasPrivate("x"));
        object.setPrivate("x", new JsNumber(7));
        assertTrue(object.hasPrivate("x"));
        assertEquals(7, ((JsNumber) Objects.requireNonNull(object.getPrivate("x"))).getValue());
    }

    // Function values expose their name and native functions invoke their implementation
    @Test
    public void test_function_values() {
        final var function = new JsFunction("f", List.of(), null, true, true, Environment.global());
        assertEquals("f", function.getName());
        assertTrue(function.isArrow());
        assertTrue(function.isExpressionBody());
        final var native1 = new JsNativeFunction("sum",
                (_, args) -> new JsNumber(((JsNumber) args.getFirst()).getValue() + 1));
        assertEquals("sum", native1.getName());
        assertEquals(2, ((JsNumber) native1.invoke(JsUndefined.getInstance(), List.of(new JsNumber(1)))).getValue());
    }

    // Singletons and boolean constants keep a single identity
    @Test
    public void test_singletons_identity() {
        assertSame(JsBoolean.TRUE, JsBoolean.of(true));
        assertSame(JsBoolean.FALSE, JsBoolean.of(false));
    }

    // Primitive wrappers expose their raw values
    @Test
    public void test_primitive_getters() {
        assertEquals(3.5, new JsNumber(3.5).getValue());
        assertEquals("hi", new JsString("hi").getValue());
        assertTrue(JsBoolean.TRUE.getValue());
        assertFalse(JsBoolean.FALSE.getValue());
        assertEquals(BigInteger.TEN, new JsBigInt(BigInteger.TEN).getValue());
    }

    // Object get/set/has/delete behave like a property map, missing keys yield undefined
    @Test
    public void test_object_property_operations() {
        final var object = new JsObject();
        assertInstanceOf(JsUndefined.class, object.get("missing"));
        object.set("a", new JsNumber(1));
        assertTrue(object.has("a"));
        assertEquals(1, ((JsNumber) object.get("a")).getValue());
        assertTrue(object.delete("a"));
        assertFalse(object.has("a"));
        assertTrue(object.keys().isEmpty());
    }

    // Array indexing returns undefined out of range, set extends with undefined holes
    @Test
    public void test_array_operations() {
        final var array = new JsArray(List.of(new JsNumber(1), new JsNumber(2)));
        assertEquals(2, array.length());
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertInstanceOf(JsUndefined.class, array.get(5));
        assertInstanceOf(JsUndefined.class, array.get(-1));
        array.push(new JsNumber(3));
        assertEquals(3, array.length());
        array.set(5, new JsNumber(9));
        assertEquals(6, array.length());
        assertInstanceOf(JsUndefined.class, array.get(4));
        assertEquals(9, ((JsNumber) array.get(5)).getValue());
    }
}
