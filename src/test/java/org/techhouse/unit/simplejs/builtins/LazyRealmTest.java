package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.FetchBuiltins;
import org.techhouse.simplejs.builtins.GlobalFunctionsBuiltins;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.TemporalBuiltins;
import org.techhouse.simplejs.builtins.TimerBuiltins;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.GlobalSink;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class LazyRealmTest {
    private static Intrinsics intrinsics() {
        return new Intrinsics((fn, thisArg, args) -> ((JsNativeFunction) fn).invoke(thisArg, args), null,
                new EventLoop(), (_, _, _) -> JsUndefined.getInstance());
    }

    private static String run(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static List<String> declaredBy(Consumer<GlobalSink> installer) {
        final var names = new ArrayList<String>();
        installer.accept((name, _) -> names.add(name));
        return names;
    }

    @Test
    public void test_a_fresh_realm_sets_up_no_prototype_until_touched() {
        final var realm = intrinsics();
        for (final var proto : List.of(realm.objectProto, realm.functionProto, realm.arrayProto, realm.stringProto,
                realm.promiseProto, realm.mapProto, realm.typedArrayProto, realm.temporalDurationProto,
                realm.arrayIteratorProto, realm.regexpStringIteratorProto)) {
            assertFalse(proto.isSetUp());
        }
        assertInstanceOf(JsNativeFunction.class, realm.arrayProto.get("map"));
        assertTrue(realm.arrayProto.isSetUp());
        assertFalse(realm.stringProto.isSetUp());
        assertSame(realm.arrayProto.get("values"), realm.defaultArrayIterator());
        assertSame(realm.typedArrayProto.get("values"), realm.defaultTypedArrayIterator());
        assertSame(realm.arrayProto.get("toString"), realm.typedArrayProto.get("toString"));
    }

    @Test
    public void test_installing_the_globals_creates_no_constructor_until_read() {
        final var realm = intrinsics();
        final var global = Environment.global();
        GlobalScope.install(global, new EventLoop(), (_, _, _) -> JsUndefined.getInstance(), _ -> List.of(), null, null,
                null, ResourceLimits.unlimited(), realm);
        assertFalse(realm.arrayProto.isSetUp());
        assertFalse(realm.promiseProto.isSetUp());
        assertTrue(global.allGlobalNames().containsAll(List.of("Array", "Promise", "Temporal", "parseInt", "fetch")));
        final var array = global.get("Array");
        assertFalse(realm.arrayProto.isSetUp());
        assertSame(array, realm.arrayProto.get("constructor"));
        assertSame(((JsNativeFunction) global.get("Number")).getProperty("parseInt"), global.get("parseInt"));
        final var duration = ((JsObject) global.get("Temporal")).get("Duration");
        assertSame(duration, realm.temporalDurationProto.get("constructor"));
    }

    @Test
    public void test_a_constructor_survives_deleting_its_global() {
        assertEquals("true,true,undefined",
                run("const m = new Set(); delete globalThis.Array; delete globalThis.Map;"
                        + " [[].constructor.name === 'Array', Object.getPrototypeOf(m).constructor.name === 'Set',"
                        + " typeof Array].join(',')"));
        assertEquals("true", run("const A = Array; delete globalThis.Array; String([].constructor === A)"));
    }

    @Test
    public void test_the_global_parsers_are_numbers_own_even_after_a_patch() {
        assertEquals("true", run("String(Number.parseInt === parseInt && Number.parseFloat === parseFloat)"));
        assertEquals("function", run("Number.parseInt = 1; typeof parseInt"));
        assertEquals("true", run("const p = parseInt; String(Number.parseInt === p)"));
    }

    @Test
    public void test_engine_errors_use_the_realm_constructors() {
        assertEquals("true,true", run("let a; let b;" + " try { null.x; } catch (e) { a = e instanceof TypeError; }"
                + " try { undefinedName; } catch (e) { b = e instanceof ReferenceError; } [a, b].join(',')"));
    }

    @Test
    public void test_generator_and_iterator_chains_are_intact() {
        assertEquals("[object Generator],true,true", run("function* g() { yield 1; } const it = g();"
                + " [Object.prototype.toString.call(it), Object.getPrototypeOf(g.prototype) === Object.getPrototypeOf(g).prototype,"
                + " Object.getPrototypeOf(Object.getPrototypeOf(Object.getPrototypeOf(it))) === Iterator.prototype].join(',')"));
        assertEquals("true", run("String(Set.prototype.keys === Set.prototype.values)"));
        assertEquals("true", run("String([][Symbol.iterator] === Array.prototype.values)"));
    }

    @Test
    public void test_an_early_patch_does_not_leak_into_a_later_prototype() {
        assertEquals("0,0", run("Array.prototype.toString = () => 'patched'; new Uint8Array(2).toString()"));
        assertEquals("3", run("String.prototype.at = null; String([...'abc'].length)"));
    }

    @Test
    public void test_each_group_installer_declares_exactly_its_names() {
        final var realm = intrinsics();
        final var loop = new EventLoop();
        assertEquals(TimerBuiltins.NAMES, declaredBy(sink -> TimerBuiltins.install(sink, loop, null)));
        assertEquals(GlobalFunctionsBuiltins.NAMES,
                declaredBy(sink -> GlobalFunctionsBuiltins.install(sink, loop, null, null, realm)));
        assertEquals(FetchBuiltins.NAMES,
                declaredBy(sink -> FetchBuiltins.install(sink, loop, null, ResourceLimits.unlimited(), realm, null)));
        assertEquals(TemporalBuiltins.NAMES, declaredBy(sink -> TemporalBuiltins.install(sink, realm, null)));
    }
}
