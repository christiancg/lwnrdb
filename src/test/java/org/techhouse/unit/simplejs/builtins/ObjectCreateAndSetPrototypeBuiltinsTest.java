package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.object.ObjectDescriptors;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.test.JsEval;

public class ObjectCreateAndSetPrototypeBuiltinsTest {
    // create sets the prototype; getPrototypeOf/setPrototypeOf round-trip
    @Test
    public void test_create_and_prototype_of() {
        assertEquals(1, JsEval.num("let p = {a: 1}; let o = Object.create(p); o.a"));
        assertTrue(((JsBoolean) Interpreter.run("let p = {}; let o = Object.create(p); Object.getPrototypeOf(o) === p"))
                .getValue());
        assertEquals(7, JsEval.num("let o = {}; Object.setPrototypeOf(o, {x: 7}); o.x"));
    }

    // Object.create(null) has a null prototype
    @Test
    public void test_create_null_proto() {
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class,
                Interpreter.run("Object.getPrototypeOf(Object.create(null))"));
    }

    // prototype-chain reads walk multiple links
    @Test
    public void test_prototype_chain_read() {
        assertEquals(9, JsEval.num("let a = {x: 9}; let b = Object.create(a); let c = Object.create(b); c.x"));
    }

    // create accepts a second properties argument
    @Test
    public void test_create_with_props() {
        assertEquals(9, JsEval.num("let o = Object.create({}, {v: {value: 9}}); o.v"));
    }

    // setPrototypeOf with a non-object clears the prototype
    @Test
    public void test_set_prototype_of_null() {
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class, Interpreter
                .run("let o = Object.create({a: 1}); Object.setPrototypeOf(o, null); Object.getPrototypeOf(o)"));
    }

    // getOwnPropertyNames of an arrow function (no prototype) omits 'prototype' from the metadata keys
    @Test
    public void test_get_own_property_names_of_arrow_function_omits_prototype() {
        assertEquals("length,name", JsEval.str("Object.getOwnPropertyNames(() => {}).join(',')"));
    }

    // OrdinarySetPrototypeOf rejects a prototype that already inherits from the target
    @Test
    public void test_set_prototype_of_detects_cycle() {
        assertEquals("TypeError", JsEval.str("""
                let caught = 'none';
                const parent = {};
                const child = Object.create(parent);
                try { Object.setPrototypeOf(parent, child); } catch (e) { caught = e.name; }
                caught
                """));
        assertEquals("TypeError", JsEval.str("""
                let caught = 'none';
                const self = {};
                try { Object.setPrototypeOf(self, self); } catch (e) { caught = e.name; }
                caught
                """));
        assertTrue(
                JsEval.bool("const a = {}; const b = {}; Object.setPrototypeOf(a, b); Object.getPrototypeOf(a) === b"));
    }

    // An object literal is linked to %Object.prototype%
    @Test
    public void test_object_literal_is_linked_to_object_prototype() {
        assertTrue(JsEval.bool("Object.getPrototypeOf({}) === Object.prototype"));
        assertTrue(JsEval.bool("Object.create({}) instanceof Object"));
    }

    @Test
    public void createRejectsANonObjectPrototypeAndNullProperties() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create(5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create('x')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create({}, null)"));
    }

    // hasOwnProperty/propertyIsEnumerable answer for symbol keys, and coerce their argument through
    // ToPropertyKey (so a @@toPrimitive wrapper yielding a symbol keys by that symbol).
    @Test
    public void objectPrototypeHelpersAnswerForSymbolKeys() {
        assertTrue(JsEval.bool("""
                const o = {};
                const s = Symbol();
                o[s] = 0;
                const wrapper = {};
                wrapper[Symbol.toPrimitive] = () => s;
                o.hasOwnProperty(wrapper) && o.hasOwnProperty(s)
                """));
        assertTrue(JsEval.bool("""
                const o = {};
                const enumerableSymbol = Symbol();
                const hiddenSymbol = Symbol();
                o[enumerableSymbol] = 1;
                Object.defineProperty(o, hiddenSymbol, { value: 1, enumerable: false });
                o.propertyIsEnumerable(enumerableSymbol) && !o.propertyIsEnumerable(hiddenSymbol)
                """));
        // ToPropertyKey precedes ToObject, so the key coercion is what escapes - the null receiver's
        // own TypeError is never reached.
        assertEquals("RangeError", JsEval.str("""
                let caught = 'no throw';
                try {
                  Object.prototype.hasOwnProperty.call(null, { toString() { throw new RangeError('k'); } });
                } catch (e) { caught = e.name; }
                caught
                """));
    }

    // Object.setPrototypeOf on a non-extensible target throws, but a no-op (setting the same
    // prototype it already has) is allowed even though the target isn't extensible.
    @Test
    public void setPrototypeOfRejectsANonExtensibleTarget() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const o = Object.preventExtensions({}); Object.setPrototypeOf(o, {})"));
        assertTrue(JsEval.bool("""
                const proto = {};
                const o = Object.create(proto);
                Object.preventExtensions(o);
                Object.setPrototypeOf(o, proto) === o
                """));
        // A plain object literal is already linked to Object.prototype (never a bare Java null), so
        // retargeting a non-extensible one to null is a real change and still rejected.
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.setPrototypeOf(Object.preventExtensions({}), null)"));
    }

    // Object.setPrototypeOf validates its arguments before it ever looks at the target's
    // extensibility: a nullish target and a non-object/non-null proto both throw.
    @Test
    public void setPrototypeOfValidatesItsArguments() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf(undefined, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf(null, {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({}, 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.setPrototypeOf({}, 'x')"));
        // A primitive target is simply returned unchanged once the proto argument passes validation.
        assertEquals(5, JsEval.num("Object.setPrototypeOf(5, {})"));
    }

    // trySetPrototypeOf is the real OrdinarySetPrototypeOf(V) boolean (used by Reflect.setPrototypeOf
    // and any other caller that must report failure without throwing) - argument validation still
    // throws unconditionally, a primitive target is a no-op success, and a same-value reassignment
    // short-circuits before the extensible/cyclic/immutable-prototype checks.
    @Test
    public void trySetPrototypeOfValidatesArgumentsAndHandlesNoOps() {
        assertThrows(TypeErrorException.class,
                () -> ObjectDescriptors.trySetPrototypeOf(JsUndefined.getInstance(), new JsObject(), null));
        assertThrows(TypeErrorException.class,
                () -> ObjectDescriptors.trySetPrototypeOf(new JsObject(), new JsNumber(1), null));
        assertTrue(ObjectDescriptors.trySetPrototypeOf(new JsNumber(1), new JsObject(), null));
        final var proto = new JsObject();
        final var target = new JsObject();
        target.setProto(proto);
        assertTrue(ObjectDescriptors.trySetPrototypeOf(target, proto, null));
    }

    // A genuine, non-cyclic, extensible target actually gets re-linked (the real mutation path, as
    // opposed to the SameValue no-op covered above).
    @Test
    public void trySetPrototypeOfMutatesAnOrdinaryExtensibleTarget() {
        final var target = new JsObject();
        final var newProto = new JsObject();
        assertTrue(ObjectDescriptors.trySetPrototypeOf(target, newProto, null));
        assertEquals(newProto, target.getProto());
    }

    // The ordinary (non-exotic) rejection reasons - a non-extensible target, a cyclic proto - answer
    // false rather than throwing (Object.setPrototypeOf's closure is what turns that into a
    // TypeError; Reflect.setPrototypeOf surfaces the false straight through).
    @Test
    public void trySetPrototypeOfReturnsFalseForNonExtensibleOrCyclicTargets() {
        final var target = new JsObject();
        target.preventExtensions();
        assertFalse(ObjectDescriptors.trySetPrototypeOf(target, new JsObject(), null));

        final var grandparent = new JsObject();
        final var parent = new JsObject();
        parent.setProto(grandparent);
        assertFalse(ObjectDescriptors.trySetPrototypeOf(grandparent, parent, null));
    }

    // 9.4.7.1: %Object.prototype% is an immutable-prototype exotic object - [[SetPrototypeOf]]
    // answers false for any distinct value even though it is otherwise extensible and the proto is
    // non-cyclic, which is exactly what distinguishes it from an ordinary object (the sibling test
    // above shows an ordinary extensible object accepts the same kind of change).
    @Test
    public void trySetPrototypeOfRejectsAnyChangeToTheImmutableObjectPrototype() {
        final var intrinsics = new Intrinsics((fn, thisArg, args) -> ((JsNativeFunction) fn).invoke(thisArg, args),
                null, new EventLoop(), (_, _, _) -> JsUndefined.getInstance());
        final var objectProto = intrinsics.objectProto;

        final var anotherProto = new JsObject();
        assertFalse(ObjectDescriptors.trySetPrototypeOf(objectProto, new JsObject(), intrinsics));
        assertFalse(ObjectDescriptors.trySetPrototypeOf(objectProto, anotherProto, intrinsics));
        // SameValue(V, current) still short-circuits to true even for the immutable-prototype object
        // (objectProto's own [[Prototype]] is null here, i.e. JsNull from a script's point of view).
        assertTrue(ObjectDescriptors.trySetPrototypeOf(objectProto, JsNull.getInstance(), intrinsics));
    }

    // fromEntries: the result is proto-linked to %Object.prototype% (OrdinaryObjectCreate), symbol
    // keys are supported (ToPropertyKey can yield one), and "0"/"1" are read via Get rather than by
    // iterating the entry - an entry whose own [Symbol.iterator] throws must never be touched.
    @Test
    public void fromEntriesLinksPrototypeAndSupportsSymbolKeysAndPlainObjectEntries() {
        assertTrue(JsEval.bool("Object.getPrototypeOf(Object.fromEntries([])) === Object.prototype"));
        assertTrue(JsEval.bool("""
                const key = Symbol('k');
                Object.fromEntries([[key, 'value']])[key] === 'value'
                """));
        assertEquals("first value", JsEval.str("""
                const entry = {
                    '0': 'first key',
                    '1': 'first value',
                    get [Symbol.iterator]() { throw new Error('must not iterate the entry'); },
                };
                Object.fromEntries([entry])['first key']
                """));
    }

    // Object.create's Properties argument is ToObject'd, so a non-empty string is walked by its
    // boxed index characters - each of which is a bare string value, not a descriptor object.
    @Test
    public void createRejectsANonEmptyStringPropertiesArgument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.create({}, 'hello')"));
    }

    // Object.getPrototypeOf/getOwnPropertyDescriptor ToObject their argument, so null/undefined is
    // a TypeError rather than a silent null/undefined answer.
    @Test
    public void getPrototypeOfRequiresAnObjectCoercibleArgument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getPrototypeOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.getPrototypeOf(null)"));
        assertTrue(JsEval.bool("Object.getPrototypeOf(5) === Number.prototype"));
    }

    // Object.create(null)'s null [[Prototype]] is the real, terminal answer: no Object.prototype
    // method should be synthesised for it (the ambiguity between "never linked" and "deliberately
    // null" that used to collapse both cases into the same ordinary-object default).
    @Test
    public void createNullHasNoInheritedObjectPrototypeMembers() {
        assertTrue(JsEval.bool("Object.create(null).hasOwnProperty === undefined"));
        assertTrue(JsEval.bool("typeof Object.create(null).toString === 'undefined'"));
    }

    // Object.setPrototypeOf(o, null) marks o just as deliberately proto-less as Object.create(null).
    @Test
    public void setPrototypeOfNullAlsoDropsInheritedMembers() {
        assertTrue(JsEval.bool("let o = {}; Object.setPrototypeOf(o, null); o.hasOwnProperty === undefined"));
    }

    // A plain object's own auto-created prototype-less object (e.g. a fresh {} that never had its
    // proto touched) still falls back to the intrinsic Object.prototype default - only the
    // deliberately-nulled case opts out.
    @Test
    public void ordinaryObjectStillInheritsObjectPrototype() {
        assertTrue(JsEval.bool("typeof ({}).hasOwnProperty === 'function'"));
    }

    // ToPropertyKey/ToNumber on a null-proto object with no reachable valueOf/toString throws,
    // instead of silently finding Object.prototype.toString.
    @Test
    public void nullProtoObjectCannotBeCoercedToAPrimitive() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("''.charAt(Object.create(null))"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class C { get [Object.create(null)]() {} }"));
    }

    // Object.groupBy returns a genuinely null-prototype object (OrdinaryObjectCreate(null)).
    @Test
    public void groupByResultHasNullPrototype() {
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class,
                Interpreter.run("Object.getPrototypeOf(Object.groupBy([1, 2, 3], x => x % 2))"));
        assertTrue(JsEval.bool("Object.groupBy([1], x => x).hasOwnProperty === undefined"));
    }
}
