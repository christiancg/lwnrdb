package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.techhouse.simplejs.builtins.MapBuiltins;
import org.techhouse.simplejs.builtins.SetBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;

// [[MapData]]/[[SetData]] are append-with-tombstone lists, so every case here runs under a timeout:
// a cursor that fails to terminate is a hang, and a hang must fail the build rather than stall it.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MapSetProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // an entry appended while forEach is running is visited before the walk ends
    @Test
    public void test_foreach_visits_entries_added_during_iteration() {
        assertEquals("1,2,3", str("let s = new Set([1, 2]); let seen = [];"
                + "s.forEach(v => { seen.push(v); if (v === 2) s.add(3); }); seen.join(',')"));
        assertEquals("a,b", str("let m = new Map([['a', 1]]); let seen = [];"
                + "m.forEach((v, k) => { seen.push(k); if (k === 'a') m.set('b', 2); }); seen.join(',')"));
    }

    // an entry deleted ahead of the cursor is skipped, never visited
    @Test
    public void test_foreach_skips_entries_deleted_ahead_of_the_cursor() {
        assertEquals("1,3", str("let s = new Set([1, 2, 3]); let seen = [];"
                + "s.forEach(v => { if (v === 1) s.delete(2); seen.push(v); }); seen.join(',')"));
    }

    // a member deleted after it was visited and then re-added takes a fresh slot, so it is revisited
    @Test
    public void test_foreach_revisits_a_deleted_then_readded_member() {
        assertEquals("1,2,3,1",
                str("let s = new Set([1, 2, 3]); let seen = [];"
                        + "s.forEach(v => { seen.push(v); if (v === 2) s.delete(1); if (v === 3) s.add(1); });"
                        + "seen.join(',')"));
    }

    // clear() empties the data list without truncating it, so a live iterator reports done
    @Test
    public void test_clear_leaves_a_live_iterator_exhausted() {
        assertTrue(bool("let m = new Map([[1, 1], [2, 2], [3, 3]]); let e = m.entries();"
                + "e.next(); m.clear(); let n = e.next(); n.done === true && n.value === undefined"));
    }

    // an iterator opened before a mutation observes it, unlike a snapshot
    @Test
    public void test_value_iterator_is_live() {
        assertEquals("1,3", str("let s = new Set([1, 2]); let it = s.values();"
                + "let out = [it.next().value]; s.delete(2); s.add(3);" + "out.push(it.next().value); out.join(',')"));
    }

    // an exhausted iterator stays exhausted even after the source grows again
    @Test
    public void test_exhausted_iterator_is_terminal() {
        assertTrue(bool("let s = new Set([1]); let it = s.values(); it.next();"
                + "let first = it.next().done; s.add(2); first === true && it.next().done === true"));
    }

    // a set/delete loop must not grow the backing list without bound
    @Test
    public void test_repeated_delete_and_readd_stays_bounded() {
        assertEquals(1, num("let m = new Map(); for (let i = 0; i < 5000; i++) { m.set('k', i); m.delete('k'); }"
                + "m.set('k', 1); m.size"));
    }

    // the seven set methods walk a live receiver: a `has` that deletes ahead of the cursor is observed
    @Test
    public void test_set_like_argument_mutating_the_receiver() {
        assertTrue(bool("let base = new Set(['a', 'b', 'c']);"
                + "let evil = { size: 3, has(v) { if (v === 'a') base.delete('c'); return ['x', 'a', 'b'].includes(v); },"
                + "  keys() { throw new Error('keys must not be used'); } };"
                + "base.isSubsetOf(evil) === true && [...base].join(',') === 'a,b'"));
    }

    // every collection constructor is constructor-only
    @Test
    public void test_constructors_require_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Map()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Map([])"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Set()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("WeakMap()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("WeakSet()"));
    }

    // ...but a subclass's super() call still reaches the native constructor
    @Test
    public void test_subclassing_still_constructs() {
        assertEquals(2, num("class M extends Map {} new M([[1, 2]]).get(1)"));
        assertEquals(2, num("class S extends Set {} new S([1, 2]).size"));
    }

    // a non-callable callback is rejected before anything is visited
    @Test
    public void test_foreach_and_groupby_reject_a_non_callable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Map([[1, 1]]).forEach()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Set([1]).forEach(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Map.groupBy([1], null)"));
    }

    // `size` is a brand-checked accessor on the prototype, not a data property
    @Test
    public void test_installed_size_accessor() {
        final var proto = new JsObject();
        MapBuiltins.installSizeAccessor(proto, false);
        final var getter = (JsNativeFunction) proto.getAccessorGetter("size");
        final var map = new JsMap();
        map.set(new JsString("a"), new JsNumber(1));
        assertEquals(1, ((JsNumber) getter.invoke(map, java.util.List.of())).getValue());
        assertFalse(proto.getFlags("size").enumerable());
        assertThrows(TypeErrorException.class, () -> getter.invoke(new JsObject(), java.util.List.of()));
        assertThrows(TypeErrorException.class, () -> getter.invoke(new JsMap(true), java.util.List.of()));
    }

    // Set.prototype.keys is not a method of its own: it is the very same object as `values`
    @Test
    public void test_installed_set_accessors_alias_keys_to_values() {
        final var proto = new JsObject();
        proto.set("values", new JsNativeFunction("values", (_, _) -> JsBoolean.TRUE));
        SetBuiltins.installAccessors(proto, false);
        assertEquals(proto.get("values"), proto.get("keys"));
        final var getter = (JsNativeFunction) proto.getAccessorGetter("size");
        final var set = new JsSet();
        set.add(new JsNumber(1));
        assertEquals(1, ((JsNumber) getter.invoke(set, java.util.List.of())).getValue());
        assertThrows(TypeErrorException.class, () -> getter.invoke(new JsSet(true), java.util.List.of()));
    }
}
