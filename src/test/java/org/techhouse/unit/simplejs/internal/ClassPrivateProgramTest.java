package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ClassPrivateProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run(
                "class C {\n  get #m() { return 7; }\n  B = class { method(o) { return o.#m; } };\n}\nvar c = new C();\nnew c.B().method(c)\n"))
                .getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_class_factory_evaluations_do_not_share_a_slot() {
        final var source = """
                function factory() {
                  return class {
                    #x = 'v';
                    read() { return this.#x; }
                  };
                }
                var A = factory(), B = factory();
                var a = new A();
                var out = A.prototype.read.call(a);
                try { B.prototype.read.call(a); out += '|leak'; }
                catch (e) { out += '|' + e.name; }
                out
                """;
        assertEquals("v|TypeError", str(source));
    }

    @Test
    public void test_private_static_members_are_per_evaluation() {
        final var source = """
                function factory() {
                  return class { static #m = 'v'; static access() { return this.#m; } };
                }
                var C1 = factory(), C2 = factory();
                var out = C1.access() + C2.access();
                try { C1.access.call(C2); out += '|leak'; }
                catch (e) { out += '|' + e.name; }
                out
                """;
        assertEquals("vv|TypeError", str(source));
    }

    @Test
    public void test_second_brand_add_throws() {
        final var source = """
                class Base { constructor(o) { return o; } }
                class C extends Base { #m() {} }
                var obj = {};
                new C(obj);
                var out = 'first';
                try { new C(obj); out += '|second'; } catch (e) { out += '|' + e.name; }
                out
                """;
        assertEquals("first|TypeError", str(source));
    }

    @Test
    public void test_private_name_reaches_a_nested_class() {
        assertEquals(7, num());
    }

    @Test
    public void test_shadowed_private_name_answers_per_declaration() {
        final var source = """
                var Child;
                class Parent {
                  #field;
                  static init() {
                    Child = class { #field; static isNameIn(v) { return #field in v; } };
                  }
                }
                Parent.init();
                Child.isNameIn(new Parent()) === false && Child.isNameIn(new Child()) === true
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_private_name_is_visible_in_a_computed_key() {
        final var source = """
                var self = {};
                var out = 'none';
                try { class C { [self.#f] = 1; #f = 'foo'; } }
                catch (e) { out = e.name; }
                out
                """;
        assertEquals("TypeError", str(source));
    }

    @Test
    public void test_brand_check_inside_a_nested_arrow() {
        final var source = """
                class C {
                  #x = 1;
                  static probe = (o) => #x in o;
                }
                C.probe(new C()) === true && C.probe({}) === false
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_private_field_on_a_non_extensible_receiver_throws() {
        final var source = """
                class C { #g = (Object.preventExtensions(this), 'v'); }
                var out = 'none';
                try { new C(); } catch (e) { out = e.name; }
                out
                """;
        assertEquals("TypeError", str(source));
    }
}
