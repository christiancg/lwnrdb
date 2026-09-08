package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

/**
 * Writing through a `super` reference. The base for the [[Set]] is the home object's prototype, but the
 * receiver is `this` - so an absent setter writes an own property on the instance rather than on the parent.
 */
public class SuperMemberWriteTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_super_assignment_without_a_setter_writes_an_own_property_on_this() {
        assertTrue(bool("""
                class A {}
                class B extends A {
                    m() { super.x = 1; return Object.getOwnPropertyNames(this).includes('x') && this.x === 1; }
                }
                new B().m()
                """));
    }

    // The receiver is `this`, so an inherited setter runs with the instance as its this value
    @Test
    public void test_super_assignment_runs_an_inherited_setter_on_the_instance() {
        assertEquals("7", str("""
                class A { set y(v) { this.seen = String(v); } }
                class B extends A { m() { super.y = 7; return this.seen; } }
                new B().m()
                """));
    }

    @Test
    public void test_super_read_finds_an_inherited_getter() {
        assertEquals(42, num("""
                class A { get y() { return 42; } }
                class B extends A { m() { return super.y; } }
                new B().m()
                """));
    }

    // A static method's home object is the class, so GetSuperBase() is the class's own prototype - the
    // parent class - while the receiver is still `this`, the class the method was called on. The write
    // therefore lands on the derived class and leaves the parent alone.
    @Test
    public void test_super_assignment_in_a_static_method_writes_on_the_receiving_class() {
        assertEquals("absent:5:true", str("""
                class A {}
                class B extends A {
                    static go() {
                        super.st = 5;
                        return ['st' in A ? 'present' : 'absent', B.st, Object.hasOwn(B, 'st')].join(':');
                    }
                }
                B.go()
                """));
    }

    // The setter on the parent still runs, but with the receiving class as its `this`
    @Test
    public void test_super_assignment_in_a_static_method_runs_the_parents_setter_on_the_receiver() {
        assertEquals("Derived:absent", str("""
                class A { static set tag(v) { this.seen = this.name; } }
                class Derived extends A { static go() { super.tag = 1; } }
                Derived.go();
                [Derived.seen, 'seen' in A ? 'present' : 'absent'].join(':')
                """));
    }

    // A non-writable property anywhere on the super base's chain refuses the write outright, rather than
    // letting it fall through and create an own property on the receiver
    @Test
    public void test_super_assignment_to_a_non_writable_property_throws() {
        assertEquals("TypeError:absent", str("""
                class A {}
                Object.defineProperty(A.prototype, 'ro', { value: 1, writable: false });
                class B extends A {
                    m() {
                        let name = 'no-throw';
                        try { super.ro = 2; } catch (e) { name = e.constructor.name; }
                        return [name, Object.hasOwn(this, 'ro') ? 'present' : 'absent'].join(':');
                    }
                }
                new B().m()
                """));
    }

    @Test
    public void test_a_compound_super_assignment_to_a_non_writable_property_throws() {
        assertEquals("TypeError:TypeError", str("""
                class A {}
                Object.defineProperty(A.prototype, 'ro', { value: 1, writable: false });
                class B extends A {
                    m() {
                        const outcomes = [];
                        try { super.ro += 1; } catch (e) { outcomes.push(e.constructor.name); }
                        try { super.ro++; } catch (e) { outcomes.push(e.constructor.name); }
                        return outcomes.join(':');
                    }
                }
                new B().m()
                """));
    }

    // The refusal follows the whole chain, not just the immediate base
    @Test
    public void test_super_assignment_to_an_inherited_non_writable_property_throws() {
        assertEquals("TypeError", str("""
                class A {}
                Object.defineProperty(A.prototype, 'deep', { value: 1, writable: false });
                class B extends A {}
                class C extends B {
                    m() {
                        try { super.deep = 2; return 'no-throw'; } catch (e) { return e.constructor.name; }
                    }
                }
                new C().m()
                """));
    }

    @Test
    public void test_a_static_super_assignment_to_a_non_writable_property_throws() {
        assertEquals("TypeError", str("""
                class A {}
                Object.defineProperty(A, 'ro', { value: 1, writable: false });
                class B extends A {
                    static s() {
                        try { super.ro = 2; return 'no-throw'; } catch (e) { return e.constructor.name; }
                    }
                }
                B.s()
                """));
    }

    @Test
    public void test_an_object_literal_super_assignment_to_a_non_writable_property_throws() {
        assertEquals("TypeError", str("""
                const proto = {};
                Object.defineProperty(proto, 'ro', { value: 1, writable: false });
                const obj = {
                    m() { try { super.ro = 2; return 'no-throw'; } catch (e) { return e.constructor.name; } }
                };
                Object.setPrototypeOf(obj, proto);
                obj.m()
                """));
    }

    // Freezing the base only freezes the base: a key it never had is still created on the instance
    @Test
    public void test_a_frozen_super_base_does_not_block_a_new_key_on_the_instance() {
        assertTrue(bool("""
                class A {}
                Object.freeze(A.prototype);
                class B extends A { m() { super.fresh = 1; return this.fresh === 1 && Object.hasOwn(this, 'fresh'); } }
                new B().m()
                """));
    }

    // super.n reads the prototype chain, never the instance - so the compound assignment folds undefined
    @Test
    public void test_compound_super_assignment_reads_the_prototype_not_the_instance() {
        assertTrue(bool("""
                class A {}
                class B extends A {
                    constructor() { super(); this.n = 1; }
                    m() { super.n += 4; return Number.isNaN(this.n); }
                }
                new B().m()
                """));
    }

    @Test
    public void test_compound_super_assignment_folds_an_inherited_value() {
        assertEquals(14, num("""
                class A { constructor() {} }
                A.prototype.n = 10;
                class B extends A { m() { super.n += 4; return this.n; } }
                new B().m()
                """));
    }

    @Test
    public void test_super_update_expression_writes_through_to_the_instance() {
        assertEquals(11, num("""
                class A {}
                A.prototype.k = 10;
                class B extends A { m() { super.k++; return this.k; } }
                new B().m()
                """));
    }

    @Test
    public void test_computed_super_key_is_evaluated_before_the_right_hand_side() {
        assertEquals("key:value", str("""
                class A {}
                class B extends A {
                    m() {
                        const order = [];
                        super[(order.push('key'), 'tag')] = (order.push('value'), 1);
                        return order.join(':');
                    }
                }
                new B().m()
                """));
    }

    @Test
    public void test_object_literal_method_reads_through_super() {
        assertEquals("own+proto", str("""
                const proto = { greet() { return 'proto'; } };
                const obj = { greet() { return 'own+' + super.greet(); } };
                Object.setPrototypeOf(obj, proto);
                obj.greet()
                """));
    }

    @Test
    public void test_object_literal_method_writes_through_super() {
        assertTrue(bool("""
                const proto = {};
                const obj = { write() { super.tag = 'written'; return this.tag === 'written'; } };
                Object.setPrototypeOf(obj, proto);
                obj.write()
                """));
    }

    // The [[Set]] runs against the super base, not `this`, so a setter writing through super does not
    // re-enter itself - it reaches the instance, where the own accessor of the same name refuses the write
    @Test
    public void test_object_literal_setter_assigning_through_super_does_not_recurse() {
        assertEquals("TypeError", str("""
                const obj = { set x(v) { super.x = v; } };
                Object.setPrototypeOf(obj, {});
                let name = 'none';
                try { obj.x = 'v'; } catch (e) { name = e.constructor.name; }
                name
                """));
    }

    @Test
    public void test_object_literal_setter_writes_another_key_through_super() {
        assertEquals("v", str("""
                const obj = { set x(v) { super.y = v; } };
                Object.setPrototypeOf(obj, {});
                obj.x = 'v';
                obj.y
                """));
    }

    @Test
    public void test_super_resolves_a_symbol_keyed_method_on_the_chain() {
        assertEquals("1-2", str("""
                class A {}
                A.prototype[Symbol.iterator] = function* () { yield 1; yield 2; };
                class B extends A { m() { return [...super[Symbol.iterator]()].join('-'); } }
                new B().m()
                """));
    }

    @Test
    public void test_super_resolves_a_symbol_keyed_accessor_on_the_chain() {
        assertEquals("sym-getter", str("""
                const S = Symbol('s');
                class A {}
                Object.defineProperty(A.prototype, S, { get() { return 'sym-getter'; } });
                class B extends A { m() { return super[S]; } }
                new B().m()
                """));
    }

    @Test
    public void test_an_absent_symbol_key_on_the_super_chain_is_undefined() {
        assertTrue(bool("""
                class A {}
                class B extends A { m() { return super[Symbol('absent')] === undefined; } }
                new B().m()
                """));
    }
}
