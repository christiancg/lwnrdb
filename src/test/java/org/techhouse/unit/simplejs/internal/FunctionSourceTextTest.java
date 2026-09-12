package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.values.JsString;

public class FunctionSourceTextTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_function_declaration_source() {
        assertEquals("function f( /* a */ x ) { /* b */ return x; }",
                str("function f( /* a */ x ) { /* b */ return x; }/* after */ f.toString()"));
    }

    @Test
    public void test_async_and_generator_source() {
        assertEquals("async function f() {}", str("async function f() {} f.toString()"));
        assertEquals("function* g() {}", str("function* g() {} g.toString()"));
        assertEquals("async function* h() {}", str("async function* h() {} h.toString()"));
    }

    @Test
    public void test_function_expression_source() {
        assertEquals("function (a, b) { return a; }", str("const f = function (a, b) { return a; }; f.toString()"));
        assertEquals("function named() {}", str("const f = function named() {}; f.toString()"));
        assertEquals("async function () {}", str("const f = async function () {}; f.toString()"));
    }

    @Test
    public void test_arrow_source() {
        assertEquals("( a /* p */ ) /* q */ => /* r */ a + 1",
                str("const f = ( a /* p */ ) /* q */ => /* r */ a + 1; f.toString()"));
        assertEquals("x => { return x; }", str("const f = x => { return x; }; f.toString()"));
        assertEquals("async x => x", str("const f = async x => x; f.toString()"));
        assertEquals("async (x) => x", str("const f = async (x) => x; f.toString()"));
    }

    @Test
    public void test_object_method_source() {
        assertEquals("m /* a */ ( /* b */ ) { /* c */ }",
                str("const o = { /* before */m /* a */ ( /* b */ ) { /* c */ }/* after */ }; o.m.toString()"));
        assertEquals("async m() {}", str("const o = { async m() {} }; o.m.toString()"));
        assertEquals("* m() {}", str("const o = { * m() {} }; o.m.toString()"));
    }

    @Test
    public void test_computed_key_method_source() {
        assertEquals("[ \"a\" ](){ }", str("const o = { [ \"a\" ](){ } }; o.a.toString()"));
        assertEquals("a(){}", str("const o = { [ { a(){} }.a ](){ } }; Object.keys(o)[0]"));
    }

    @Test
    public void test_accessor_source() {
        assertEquals("get x() { return 1; }", str(
                "const o = { get x() { return 1; } };" + " Object.getOwnPropertyDescriptor(o, 'x').get.toString()"));
        assertEquals("set x(v) {}",
                str("const o = { set x(v) {} }; Object.getOwnPropertyDescriptor(o, 'x').set.toString()"));
    }

    @Test
    public void test_class_method_source() {
        assertEquals("m() { return 1; }", str("class C { m() { return 1; } } C.prototype.m.toString()"));
        assertEquals("m() {}", str("class C { static m() {} } C.m.toString()"));
        assertEquals("get v() { return 2; }", str("class C { static get v() { return 2; } }"
                + " Object.getOwnPropertyDescriptor(C, 'v').get.toString()"));
        assertEquals("#p() {}", str("class C { #p() {} read() { return this.#p.toString(); } } new C().read()"));
    }

    @Test
    public void test_class_source() {
        assertEquals("class A /* a */ { /* b */ }", str("class A /* a */ { /* b */ } A.toString()"));
        assertEquals("class B extends A { constructor() { super(); } }",
                str("class A {} class B extends A { constructor() { super(); } } B.toString()"));
        assertEquals("class { m() {} }", str("const C = class { m() {} }; C.toString()"));
    }

    @Test
    public void test_sourceless_callables_keep_the_native_form() {
        assertEquals("function map() { [native code] }", str("Array.prototype.map.toString()"));
        assertEquals("function () { [native code] }", str("function f() {} f.bind(null).toString()"));
        assertEquals("function () { [native code] }", str("function f() {} new Proxy(f, {}).toString()"));
        assertEquals("function f() { [native code] }", str("function f() {} '' + new Proxy(f, {})"));
        assertEquals("function C() { [native code] }", str("class C {} '' + new Proxy(C, {})"));
    }

    @Test
    public void test_token_only_parse_has_no_source() {
        final var program = Parser.parse(Lexer.lex("function f() { return 1; } f.toString()"));
        assertEquals("function f() { [native code] }", ((JsString) Interpreter.run(program)).getValue());
    }
}
