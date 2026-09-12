package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.test.JsEval;

public class ObjectArrayInteropBuiltinsTest {
    @Test
    public void test_over_arrays() {
        assertEquals("0,1", JsEval.str("Object.keys(['x', 'y']).join(',')"));
        assertEquals("x,y", JsEval.str("Object.values(['x', 'y']).join(',')"));
    }

    @Test
    public void test_get_own_property_names_array() {
        assertEquals("0,1,length", JsEval.str("Object.getOwnPropertyNames(['a', 'b']).join(',')"));
    }

    @Test
    public void test_array_index_key_order_unaffected() {
        assertEquals("1,2,b,a", JsEval.str("Object.keys({b: 1, 2: 1, a: 1, 1: 1}).join(',')"));
    }

    @Test
    public void test_has_own_typed_array_and_array_custom_property() {
        assertTrue(JsEval.bool("Object.hasOwn(new Int8Array(3), 1)"));
        assertFalse(JsEval.bool("Object.hasOwn(new Int8Array(3), 5)"));
        assertTrue(JsEval.bool("let a = []; a.custom = 1; Object.hasOwn(a, 'custom')"));
        assertFalse(JsEval.bool("Object.hasOwn([], 'missing')"));
    }

    @Test
    public void test_entries_over_array() {
        assertEquals("0=a,1=b", JsEval.str("Object.entries(['a', 'b']).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void test_define_property_on_array_index() {
        assertEquals(1001, JsEval
                .num("""
                        var arr = [1];
                        Object.defineProperty(arr, "0", { value: 1001, writable: false, enumerable: false, configurable: false });
                        arr[0]
                        """));
        assertTrue(JsEval
                .bool("""
                        var arr = [1];
                        Object.defineProperty(arr, "0", { value: 1001, writable: false, enumerable: false, configurable: false });
                        var d = Object.getOwnPropertyDescriptor(arr, "0");
                        d.value === 1001 && d.writable === false && d.enumerable === false && d.configurable === false
                        """));
    }

    @Test
    public void test_define_property_on_array_new_index_beyond_length() {
        assertTrue(JsEval.bool("""
                var arr = [];
                Object.defineProperty(arr, "2", { value: 9 });
                arr.length === 3 && arr[2] === 9 && arr[0] === undefined
                """));
    }

    @Test
    public void test_define_property_on_array_length() {
        assertTrue(JsEval.bool("""
                var arr = [1, 2, 3];
                Object.defineProperty(arr, "length", { value: 1 });
                arr.length === 1 && arr[1] === undefined
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "length", { writable: false });
                Object.defineProperty(arr, "length", { value: 5 });
                """));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { get() { return 1; } })"));
    }

    @Test
    public void test_define_property_on_array_named_property() {
        assertTrue(JsEval
                .bool("""
                        var arr = [];
                        Object.defineProperty(arr, "foo", { value: "bar", enumerable: true, writable: true, configurable: true });
                        arr.foo === "bar" && Object.keys(arr).includes("foo")
                        """));
    }

    @Test
    public void test_define_property_accessor_on_array_named_property() {
        assertTrue(JsEval.bool("""
                var arr = [];
                var log = [];
                Object.defineProperty(arr, "foo", {
                  get() { return 42; },
                  set(v) { log.push(v); },
                  enumerable: true,
                  configurable: true
                });
                arr.foo = 7;
                arr.foo === 42 && log[0] === 7
                """));
    }

    @Test
    public void test_define_property_accessor_on_array_index() {
        assertTrue(JsEval.bool("""
                var arr = [];
                Object.defineProperty(arr, "0", { get() { return 5; }, enumerable: true, configurable: true });
                arr[0] === 5 && arr.length === 1
                """));
    }

    @Test
    public void test_define_properties_on_array() {
        assertTrue(JsEval.bool("""
                var arr = [1, 2];
                Object.defineProperties(arr, { "0": { value: 9 }, length: { value: 1 } });
                arr[0] === 9 && arr.length === 1
                """));
    }

    @Test
    public void test_array_delete_configurable_makes_hole() {
        assertTrue(JsEval.bool("""
                var arr = [1, 2, 3];
                delete arr[1];
                !(1 in arr) && arr.length === 3
                """));
    }

    @Test
    public void test_array_delete_non_configurable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [1];
                Object.defineProperty(arr, "0", { configurable: false });
                delete arr[0];
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [];
                Object.defineProperty(arr, "foo", { value: 1, configurable: false });
                delete arr.foo;
                """));
    }

    @Test
    public void test_array_delete_absent_or_named_property() {
        assertTrue(JsEval.bool("var arr = [1]; delete arr[5]"));
        assertTrue(JsEval.bool("""
                var arr = [];
                arr.foo = 1;
                delete arr.foo;
                !('foo' in arr)
                """));
    }

    @Test
    public void test_object_extension_checks_on_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("var arr = []; Object.preventExtensions(arr); Object.defineProperty(arr, '0', {value: 1});"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("var arr = []; Object.preventExtensions(arr); Object.defineProperty(arr, 'x', {value: 1});"));
    }

    @Test
    public void test_array_keys_values_entries_respect_enumerable_and_holes() {
        assertTrue(JsEval.bool("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.keys(arr).join(',') === '1'
                """));
        assertTrue(JsEval.bool("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.values(arr).join(',') === '2'
                """));
        assertTrue(JsEval.bool("""
                var arr = [1, 2];
                Object.defineProperty(arr, "0", { enumerable: false });
                Object.entries(arr).length === 1
                """));
        assertTrue(JsEval.bool("""
                var arr = [1, 2, 3];
                delete arr[1];
                Object.getOwnPropertyNames(arr).join(',') === '0,2,length'
                """));
        assertTrue(JsEval.bool("""
                var arr = [];
                arr.foo = 1;
                Object.getOwnPropertyNames(arr).includes('foo')
                """));
    }

    @Test
    public void test_for_in_over_array_respects_enumerable_and_holes() {
        assertTrue(JsEval.bool("""
                var arr = [1, 2, 3];
                delete arr[1];
                var seen = [];
                for (var k in arr) { seen.push(k); }
                seen.join(',') === '0,2'
                """));
        assertTrue(JsEval.bool("""
                var arr = [];
                arr.foo = 1;
                var seen = [];
                for (var k in arr) { seen.push(k); }
                seen.join(',') === 'foo'
                """));
    }

    @Test
    public void test_array_index_get_own_property_descriptor_hole_is_undefined() {
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("var arr = [1,2,3]; delete arr[1]; Object.getOwnPropertyDescriptor(arr, '1')"));
    }

    @Test
    public void test_set_prototype_of_accepts_an_array() {
        assertEquals("function", JsEval.str("const o = {}; Object.setPrototypeOf(o, [1, 2]); typeof o.join"));
        assertEquals(2, JsEval.num("const o = {}; Object.setPrototypeOf(o, [1, 2]); o.length"));
        assertTrue(JsEval.bool(
                "const p = [1, 2]; const o = {}; Object.setPrototypeOf(o, p);" + " Object.getPrototypeOf(o) === p"));
        assertTrue(JsEval.bool("const p = new Map(); const o = Object.create(p); Object.getPrototypeOf(o) === p"));
    }

    @Test
    public void test_set_prototype_of_cycle_check_across_an_array_link() {
        assertEquals("TypeError", JsEval.str("""
                let caught = 'none';
                const parent = {};
                Object.setPrototypeOf(parent, [1, 2]);
                const child = Object.create(parent);
                try { Object.setPrototypeOf(parent, child); } catch (e) { caught = e.name; }
                caught
                """));
    }

    @Test
    public void test_create_with_an_array_prototype() {
        assertEquals("function", JsEval.str("typeof Object.create([1, 2, 3]).map"));
        assertEquals(3, JsEval.num("Object.create([1, 2, 3]).length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create(5)"));
    }

    @Test
    public void test_is_prototype_of_through_an_array_link() {
        assertTrue(JsEval.bool("const p = [1, 2]; Object.prototype.isPrototypeOf.call(p, Object.create(p))"));
        assertFalse(JsEval.bool("const p = [1, 2]; Object.prototype.isPrototypeOf.call(p, {})"));
    }

    @Test
    public void valuesAndEntriesIncludeAnArraysNamedProperties() {
        assertEquals("1,2,3", JsEval.str("const a = [1, 2]; a.x = 3; Object.values(a).join(',')"));
        assertEquals("0=1,1=2,x=3",
                JsEval.str("const a = [1, 2]; a.x = 3; Object.entries(a).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void arrayLengthRedefinitionIsChecked() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { configurable: true })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { enumerable: true })"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, 'length', { writable: false });
                Object.defineProperty(a, 'length', { writable: true })
                """));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { value: -1 })"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([], 'length', { value: 1.5 })"));
    }

    @Test
    public void arrayIndexRedefinitionIsChecked() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { configurable: false, enumerable: true });
                Object.defineProperty(a, '0', { enumerable: false })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { writable: false, configurable: false });
                Object.defineProperty(a, '0', { writable: true })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1];
                Object.defineProperty(a, '0', { configurable: false });
                Object.defineProperty(a, '0', { get() { return 2; } })
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [];
                Object.preventExtensions(a);
                Object.defineProperty(a, '0', { get() { return 2; } })
                """));
    }

    @Test
    public void arrayIndexAccessorsAreValidated() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([1], '0', { get: 1, configurable: true })"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.defineProperty([1], '0', { set: 1, configurable: true })"));
        assertEquals(5, JsEval.num("const a = [1]; Object.defineProperty(a, '0', { get() { return 5; } }); a[0]"));
        assertEquals(6, JsEval.num("""
                const a = [1];
                let seen = 0;
                Object.defineProperty(a, '0', { get() { return seen; }, set(v) { seen = v + 1; } });
                a[0] = 5;
                a[0]
                """));
    }

    @Test
    public void arrayOwnPropertyDescriptorsCoverEveryKeyShape() {
        assertEquals("undefined", JsEval.str("typeof Object.getOwnPropertyDescriptor([], Symbol('x'))"));
        assertEquals(2, JsEval.num("Object.getOwnPropertyDescriptor([1, 2], 'length').value"));
        assertEquals("undefined", JsEval.str("typeof Object.getOwnPropertyDescriptor([], 'nope')"));
        assertEquals(3, JsEval.num("const a = []; a.x = 3; Object.getOwnPropertyDescriptor(a, 'x').value"));
        assertTrue(JsEval.bool("""
                const a = [1];
                Object.defineProperty(a, '0', { get() { return 1; }, configurable: true });
                const d = Object.getOwnPropertyDescriptor(a, '0');
                typeof d.get === 'function' && d.set === undefined && d.configurable === true
                """));
        assertTrue(JsEval.bool("""
                const a = [];
                Object.defineProperty(a, 'x', { set(v) {}, enumerable: true });
                const d = Object.getOwnPropertyDescriptor(a, 'x');
                d.get === undefined && typeof d.set === 'function' && d.enumerable === true
                """));
    }

    @Test
    public void arraySetLengthDistinguishesRangeErrorFromTypeError() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = -1"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = 1.5"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("const a = [1]; a.length = 4294967296"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 'length', { writable: false });
                a.length = 2
                """));
        // The range check precedes the writability check, so an invalid value still reports RangeError.
        assertThrows(RangeErrorException.class, () -> Interpreter.run("""
                const a = [1, 2, 3];
                Object.defineProperty(a, 'length', { writable: false });
                a.length = -1
                """));
    }

    @Test
    public void arraySetLengthTruncationStopsAtANonConfigurableIndex() {
        assertEquals(3, JsEval.num("""
                const a = [0, 1, 2, 3];
                Object.defineProperty(a, '2', { configurable: false });
                try { a.length = 0; } catch (e) {}
                a.length
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const a = [0, 1, 2, 3];
                Object.defineProperty(a, '2', { configurable: false });
                a.length = 0
                """));
        assertEquals(1, JsEval.num("const a = [0, 1, 2, 3]; a.length = 1; a.length"));
    }

    @Test
    public void arrayIndexWriteConsultsAnInheritedSetter() {
        assertEquals("42", JsEval.str("""
                let seen = [];
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) { seen.push(v); }, get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 42;
                seen.join(',')
                """));
        assertEquals(0, JsEval.num("""
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) {}, get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 42;
                a.length
                """));
        assertEquals("7", JsEval.str("""
                let seen = [];
                const proto = {};
                Object.defineProperty(proto, 'x', { set(v) { seen.push(v); } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a.x = 7;
                seen.join(',')
                """));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const proto = {};
                Object.defineProperty(proto, '0', { get() { return 'G'; } });
                const a = [];
                Object.setPrototypeOf(a, proto);
                a[0] = 1
                """));
        assertEquals(9, JsEval.num("""
                const proto = {};
                Object.defineProperty(proto, '0', { set(v) {}, get() { return 'G'; } });
                const a = [5];
                Object.setPrototypeOf(a, proto);
                a[0] = 9;
                a[0]
                """));
    }

    @Test
    public void settingAnArrayPrototypeRedirectsInheritedReads() {
        assertEquals("inherited", JsEval.str("const a = []; Object.setPrototypeOf(a, { tag: 'inherited' }); a.tag"));
        assertEquals("undefined", JsEval.str("""
                const a = [];
                Object.setPrototypeOf(a, {});
                typeof a.map
                """));
        assertTrue(JsEval.bool("const a = [1]; Object.getPrototypeOf(a) === Array.prototype"));
    }

    @Test
    public void assignHandlesPrimitiveTargetsStringSourcesAndArrays() {
        assertEquals("object,object,object", JsEval
                .str("typeof Object.assign(true) + ',' + typeof Object.assign(1) + ',' + typeof Object.assign('s')"));
        assertEquals("1,2,3", JsEval.str("""
                const r = Object.assign({}, '123');
                r[0] + ',' + r[1] + ',' + r[2]
                """));
        assertEquals("1,8,3", JsEval.str("""
                const target = [7, 8, 9];
                Object.assign(target, [1]);
                const sparse = [];
                sparse[2] = 3;
                Object.assign(target, sparse);
                target.join(',')
                """));
    }
}
