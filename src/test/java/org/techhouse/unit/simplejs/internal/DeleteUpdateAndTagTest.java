package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

/**
 * The reference-shaped expressions: a tagged template whose tag is a member call, `delete` against the
 * various exotic property tables, and `++`/`--` against a private or super reference.
 */
public class DeleteUpdateAndTagTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A member tag is called with its object as `this`, like any other member call
    @Test
    public void test_a_member_tag_receives_its_object_as_this() {
        assertEquals("H:a|b1", str("""
                const holder = {
                    prefix: 'H:',
                    tag(strings, ...values) { return this.prefix + strings.raw.join('|') + values.join(','); }
                };
                holder.tag`a${1}b`
                """));
    }

    @Test
    public void test_a_private_method_can_be_a_template_tag() {
        assertEquals("private:p", str("""
                class P {
                    #tag(strings) { return 'private:' + strings[0]; }
                    run() { return this.#tag`p`; }
                }
                new P().run()
                """));
    }

    // A tagged template may not appear in an optional chain, and that is a parse-time refusal
    @Test
    public void test_a_tagged_template_on_an_optional_chain_is_a_syntax_error() {
        final var failure = assertThrows(SyntaxErrorException.class, () -> Interpreter.run("const o = {}; o?.tag`x`"));
        assertTrue(failure.getMessage().contains("optional chain"), failure.getMessage());
    }

    @Test
    public void test_a_private_field_supports_both_update_forms() {
        assertEquals("1/3", str("""
                class P {
                    #n = 1;
                    bump() { return [this.#n++, ++this.#n].join('/'); }
                }
                new P().bump()
                """));
    }

    // Deleting something that is not a reference still evaluates it
    @Test
    public void test_deleting_a_call_expression_evaluates_it_and_answers_true() {
        assertEquals("true:1", str("""
                let called = 0;
                function f() { called++; return {}; }
                [delete f(), called].join(':')
                """));
    }

    @Test
    public void test_deleting_a_super_reference_is_a_reference_error() {
        assertEquals("ReferenceError", str("""
                class A {}
                class B extends A {
                    m() { try { delete super.x; return 'no-throw'; } catch (e) { return e.constructor.name; } }
                }
                new B().m()
                """));
    }

    // A generator's `prototype` is non-configurable, so the strict delete throws
    @Test
    public void test_deleting_a_generators_prototype_throws() {
        assertEquals("TypeError", str("""
                let name = 'no-throw';
                try { delete (function* () {}).prototype; } catch (e) { name = e.constructor.name; }
                name
                """));
    }

    // An arrow has no `prototype` at all, so deleting the absent property succeeds
    @Test
    public void test_deleting_an_arrows_absent_prototype_succeeds() {
        assertTrue(bool("delete (() => {}).prototype"));
    }

    @Test
    public void test_deleting_a_symbol_key_reaches_the_proxy_trap() {
        assertEquals("true:absent", str("""
                const S = Symbol('s');
                const target = { [S]: 1 };
                const proxy = new Proxy(target, { deleteProperty(t, k) { return Reflect.deleteProperty(t, k); } });
                [delete proxy[S], S in target ? 'present' : 'absent'].join(':')
                """));
    }

    @Test
    public void test_deleting_a_non_configurable_symbol_key_throws() {
        assertEquals("TypeError", str("""
                const S = Symbol('s');
                const target = {};
                Object.defineProperty(target, S, { value: 1, configurable: false });
                let name = 'no-throw';
                try { delete target[S]; } catch (e) { name = e.constructor.name; }
                name
                """));
    }

    @Test
    public void test_deleting_a_static_symbol_key_of_a_class_succeeds() {
        assertTrue(bool("const S = Symbol('s'); class C { static [S] = 1; } delete C[S]"));
    }

    // Every other exotic object keeps its symbol keys in the ordinary table, so the delete lands there
    @Test
    public void test_deleting_a_symbol_key_from_a_map_reaches_its_ordinary_table() {
        assertEquals("true:absent", str("""
                const S = Symbol('s');
                const map = new Map();
                map[S] = 1;
                [delete map[S], map[S] === undefined ? 'absent' : 'present'].join(':')
                """));
    }
}
