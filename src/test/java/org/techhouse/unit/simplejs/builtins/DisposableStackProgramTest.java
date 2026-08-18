package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// disposeAsync drains the event loop, so every case runs under a timeout: a disposer chain that never
// settles must fail the build rather than stall it.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class DisposableStackProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run(
                "class D extends DisposableStack {} let out = [];let d = new D(); d.defer(() => out.push(1)); d.dispose(); out.length"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // both classes are constructor-only
    @Test
    public void test_constructors_require_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DisposableStack()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("AsyncDisposableStack()"));
    }

    // [[DisposableState]] and [[AsyncDisposableState]] are distinct brands, not one shared marker
    @Test
    public void test_sibling_class_is_not_an_acceptable_receiver() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("DisposableStack.prototype.dispose.call(new AsyncDisposableStack())"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("DisposableStack.prototype.move.call(new AsyncDisposableStack())"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("DisposableStack.prototype.use.call(new AsyncDisposableStack(), null)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("AsyncDisposableStack.prototype.move.call(new DisposableStack())"));
    }

    // the `disposed` accessor is brand-checked too, rather than answering true for anything foreign
    @Test
    public void test_disposed_accessor_brand() {
        assertTrue(bool("new DisposableStack().disposed === false"));
        assertTrue(bool("let s = new DisposableStack(); s.dispose(); s.disposed"));
        final var getter = "Object.getOwnPropertyDescriptor(DisposableStack.prototype, 'disposed').get";
        assertThrows(TypeErrorException.class, () -> Interpreter.run(getter + ".call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run(getter + ".call(undefined)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run(getter + ".call(new AsyncDisposableStack())"));
    }

    // @@dispose is the very same function object as `dispose`, which a script can compare
    @Test
    public void test_symbol_dispose_is_the_dispose_method() {
        assertTrue(bool("DisposableStack.prototype[Symbol.dispose] === DisposableStack.prototype.dispose"));
        assertTrue(bool("AsyncDisposableStack.prototype[Symbol.asyncDispose]"
                + " === AsyncDisposableStack.prototype.disposeAsync"));
    }

    // disposal runs in reverse registration order
    @Test
    public void test_disposal_is_lifo() {
        assertEquals("3,2,1",
                str("let out = []; let s = new DisposableStack();"
                        + "s.defer(() => out.push(1)); s.defer(() => out.push(2)); s.defer(() => out.push(3));"
                        + "s.dispose(); out.join(',')"));
    }

    // a disposer that throws after another already threw nests into a SuppressedError chain
    @Test
    public void test_suppressed_error_nesting() {
        assertTrue(bool("let s = new DisposableStack();"
                + "s.defer(() => { throw 'first'; }); s.defer(() => { throw 'second'; });"
                + "s.defer(() => { throw 'third'; });" + "let caught; try { s.dispose(); } catch (e) { caught = e; }"
                + "caught.name === 'SuppressedError' && caught.error === 'first'"
                + " && caught.suppressed.name === 'SuppressedError'"
                + " && caught.suppressed.error === 'second' && caught.suppressed.suppressed === 'third'"));
    }

    // every remaining disposer still runs even after one of them threw
    @Test
    public void test_a_throwing_disposer_does_not_stop_the_rest() {
        assertEquals("3,2,1",
                str("let out = []; let s = new DisposableStack();"
                        + "s.defer(() => out.push(1)); s.defer(() => { out.push(2); throw new Error('x'); });"
                        + "s.defer(() => out.push(3));" + "try { s.dispose(); } catch (e) { } out.join(',')"));
    }

    // move() transfers the resources, disposes the source, and yields an ordinary intrinsic instance
    @Test
    public void test_move_transfers_resources_and_returns_an_intrinsic_instance() {
        assertEquals("2,1",
                str("let out = []; let s = new DisposableStack();"
                        + "s.defer(() => out.push(1)); s.defer(() => out.push(2));"
                        + "let moved = s.move(); if (out.length !== 0) throw new Error('disposed too early');"
                        + "moved.dispose(); out.join(',')"));
        assertTrue(bool("let s = new DisposableStack(); s.move(); s.disposed"));
        assertTrue(bool("class D extends DisposableStack {} let moved = new D().move();"
                + "moved instanceof DisposableStack && Object.getPrototypeOf(moved) === DisposableStack.prototype"));
    }

    // a subclass instance carries the internal slots its super() call installed
    @Test
    public void test_subclass_instance_is_usable() {
        assertEquals(1, num());
    }

    // operating on a disposed stack is a ReferenceError, not a silent no-op
    @Test
    public void test_use_after_dispose() {
        assertTrue(bool("let s = new DisposableStack(); s.dispose();"
                + "let threw = false; try { s.defer(() => {}); } catch (e) { threw = e.name === 'ReferenceError'; }"
                + "threw"));
    }

    // `use` rejects a non-disposable and accepts a nullish resource
    @Test
    public void test_use_argument_validation() {
        assertTrue(bool("new DisposableStack().use(null) === null"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new DisposableStack().use({})"));
    }

    // OrdinaryCreateFromConstructor: Reflect.construct with a foreign newTarget links the instance
    // to that newTarget's own `prototype` instead of the shared DisposableStack.prototype.
    @Test
    public void test_prototype_from_new_target() {
        assertTrue(bool("Object.getPrototypeOf(Reflect.construct(DisposableStack, [], Object)) === Object.prototype"));
        assertTrue(
                bool("Object.getPrototypeOf(Reflect.construct(AsyncDisposableStack, [], Array)) === Array.prototype"));
    }

    // Get(newTarget, "prototype") is observable and runs exactly once, so a throwing accessor must
    // propagate instead of the constructor silently falling back to the shared prototype.
    @Test
    public void test_prototype_from_new_target_propagates_a_throwing_accessor() {
        assertThrows(JsThrowException.class, () -> Interpreter.run("""
                let calls = 0;
                let newTarget = function() {}.bind(null);
                Object.defineProperty(newTarget, 'prototype', {
                    get: function() { calls += 1; throw new TypeError('broken'); }
                });
                Reflect.construct(DisposableStack, [], newTarget);
                """));
    }
}
