package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;

public class InOperatorProgramTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_static_method_in_class() {
        assertTrue(bool("class C { static m() {} } 'm' in C"));
    }

    @Test
    public void test_static_field_in_class() {
        assertTrue(bool("class C { static x = 1; } 'x' in C"));
    }

    @Test
    public void test_static_getter_in_class() {
        assertTrue(bool("class C { static get g() { throw new Error('never'); } } 'g' in C"));
    }

    @Test
    public void test_static_setter_in_class() {
        assertTrue(bool("class C { static set s(v) {} } 's' in C"));
    }

    @Test
    public void test_prototype_and_name_in_class() {
        assertTrue(bool("class C {} 'prototype' in C && 'name' in C"));
    }

    @Test
    public void test_missing_key_not_in_class() {
        assertFalse(bool("class C { static m() {} } 'nope' in C"));
    }

    @Test
    public void test_inherited_static_in_subclass() {
        assertTrue(bool("class A { static s = 1; } class B extends A {} 's' in B"));
    }

    @Test
    public void test_static_symbol_method_in_class() {
        assertTrue(bool("const s = Symbol('t'); class C { static [s]() {} } s in C"));
    }

    @Test
    public void test_static_symbol_field_in_class() {
        assertTrue(bool("const s = Symbol('t'); class C { static [s] = 1; } s in C"));
    }

    @Test
    public void test_static_symbol_getter_in_class() {
        assertTrue(bool("const s = Symbol('t'); class C { static get [s]() { return 1; } } s in C"));
    }

    @Test
    public void test_missing_symbol_not_in_class() {
        assertFalse(bool("const s = Symbol('t'); class C {} s in C"));
    }

    @Test
    public void test_index_in_typed_array() {
        assertTrue(bool("'0' in new Int8Array(2)"));
    }

    @Test
    public void test_out_of_range_index_not_in_typed_array() {
        assertFalse(bool("'5' in new Int8Array(2)"));
    }

    @Test
    public void test_non_canonical_index_not_in_typed_array() {
        assertFalse(bool("'1.0' in new Int8Array(2)"));
    }

    @Test
    public void test_negative_zero_index_not_in_typed_array() {
        assertFalse(bool("'-0' in new Int8Array(2)"));
    }

    @Test
    public void test_plus_prefixed_index_is_ordinary_on_typed_array() {
        assertFalse(bool("'+1' in new Int8Array(2)"));
        assertTrue(bool("const t = new Int8Array(2); t['+1'] = 3; '+1' in t"));
    }

    @Test
    public void test_expando_in_typed_array() {
        assertTrue(bool("const t = new Int8Array(2); t.foo = 1; 'foo' in t"));
    }

    @Test
    public void test_index_in_arguments() {
        assertTrue(bool("function f() { return 0 in arguments; } f(1)"));
    }

    @Test
    public void test_out_of_range_index_not_in_arguments() {
        assertFalse(bool("function f() { return 3 in arguments; } f(1)"));
    }

    @Test
    public void test_length_in_arguments() {
        assertTrue(bool("function f() { return 'length' in arguments; } f(1)"));
    }

    @Test
    public void test_symbol_iterator_in_arguments() {
        assertTrue(bool("function f() { return Symbol.iterator in arguments; } f(1)"));
    }

    @Test
    public void test_symbol_in_prototype_chain() {
        final var source = """
                const s = Symbol('t');
                const proto = {};
                proto[s] = 1;
                const child = Object.create(proto);
                s in child
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_symbol_method_in_instance() {
        assertTrue(bool("const s = Symbol('t'); class C { [s]() {} } s in new C()"));
    }

    @Test
    public void test_symbol_key_on_an_exotic_receiver() {
        assertTrue(bool("Symbol.iterator in []"));
        assertFalse(bool("Symbol.toStringTag in []"));
        assertTrue(bool("Symbol.replace in /x/"));
        assertTrue(bool("Symbol.hasInstance in function() {}"));
        assertTrue(bool("Symbol.iterator in new Map()"));
        assertFalse(bool("Symbol('unshared') in []"));
    }

    @Test
    public void test_own_symbol_on_an_exotic_receiver() {
        assertTrue(bool("const s = Symbol('own'); const a = []; a[s] = 1; s in a"));
    }

    @Test
    public void test_left_operand_evaluated_before_right_operand() {
        final var source = """
                var target = 0;
                (target = Number, 'MAX_VALUE') in target
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_right_operand_sees_left_operand_side_effect() {
        final var source = """
                var key = 'MAX_VALUE';
                key in (key = 'none', Number)
                """;
        assertTrue(bool(source));
    }
}
