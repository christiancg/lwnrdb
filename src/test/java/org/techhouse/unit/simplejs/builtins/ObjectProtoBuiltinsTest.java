package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class ObjectProtoBuiltinsTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String str() {
        return ((JsString) Interpreter.run("({}).toString()")).getValue();
    }

    // toString on a plain object yields the default tag
    @Test
    public void test_to_string() {
        assertEquals("[object Object]", str());
    }

    // valueOf returns the object itself
    @Test
    public void test_value_of() {
        assertTrue(bool("let o = {}; o.valueOf() === o"));
    }

    // isPrototypeOf walks the prototype chain
    @Test
    public void test_is_prototype_of() {
        assertTrue(bool("let p = {}; let o = Object.create(p); p.isPrototypeOf(o)"));
        assertFalse(bool("let p = {}; let o = {}; p.isPrototypeOf(o)"));
        assertFalse(bool("({}).isPrototypeOf(5)"));
    }

    // propertyIsEnumerable reflects own-property presence
    @Test
    public void test_property_is_enumerable() {
        assertTrue(bool("({a: 1}).propertyIsEnumerable('a')"));
        assertFalse(bool("({a: 1}).propertyIsEnumerable('b')"));
    }
}
