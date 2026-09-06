package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.values.JsString;

// Function.prototype.toString hands back the construct's own source text verbatim, so every
// function-like production has to record where it started and ended - comments and all.
public class FunctionSourceTextTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // a function declaration reports its text from `function` to its closing brace, trivia inside included
    @Test
    public void test_function_declaration_source() {
        assertEquals("function f( /* a */ x ) { /* b */ return x; }",
                str("function f( /* a */ x ) { /* b */ return x; }/* after */ f.toString()"));
    }

    // the leading `async` and the generator `*` belong to the function's own text
    @Test
    public void test_async_and_generator_source() {
        assertEquals("async function f() {}", str("async function f() {} f.toString()"));
        assertEquals("function* g() {}", str("function* g() {} g.toString()"));
        assertEquals("async function* h() {}", str("async function* h() {} h.toString()"));
    }

    // a function expression's text starts at its own `function`, not at the assignment
    @Test
    public void test_function_expression_source() {
        assertEquals("function (a, b) { return a; }", str("const f = function (a, b) { return a; }; f.toString()"));
        assertEquals("function named() {}", str("const f = function named() {}; f.toString()"));
        assertEquals("async function () {}", str("const f = async function () {}; f.toString()"));
    }

    // an arrow's text runs from its parameter list (or its `async`) through the body
    @Test
    public void test_arrow_source() {
        assertEquals("( a /* p */ ) /* q */ => /* r */ a + 1",
                str("const f = ( a /* p */ ) /* q */ => /* r */ a + 1; f.toString()"));
        assertEquals("x => { return x; }", str("const f = x => { return x; }; f.toString()"));
        assertEquals("async x => x", str("const f = async x => x; f.toString()"));
        assertEquals("async (x) => x", str("const f = async (x) => x; f.toString()"));
    }

    // an object literal method reports the MethodDefinition text, key and modifiers included
    @Test
    public void test_object_method_source() {
        assertEquals("m /* a */ ( /* b */ ) { /* c */ }",
                str("const o = { /* before */m /* a */ ( /* b */ ) { /* c */ }/* after */ }; o.m.toString()"));
        assertEquals("async m() {}", str("const o = { async m() {} }; o.m.toString()"));
        assertEquals("* m() {}", str("const o = { * m() {} }; o.m.toString()"));
    }

    // a computed key is part of the method's text, which is what makes a key built from one work
    @Test
    public void test_computed_key_method_source() {
        assertEquals("[ \"a\" ](){ }", str("const o = { [ \"a\" ](){ } }; o.a.toString()"));
        assertEquals("a(){}", str("const o = { [ { a(){} }.a ](){ } }; Object.keys(o)[0]"));
    }

    // an accessor's text opens at its `get`/`set` modifier
    @Test
    public void test_accessor_source() {
        assertEquals("get x() { return 1; }", str(
                "const o = { get x() { return 1; } };" + " Object.getOwnPropertyDescriptor(o, 'x').get.toString()"));
        assertEquals("set x(v) {}",
                str("const o = { set x(v) {} }; Object.getOwnPropertyDescriptor(o, 'x').set.toString()"));
    }

    // `static` belongs to the ClassElement, not to the method, so it stays out of the span
    @Test
    public void test_class_method_source() {
        assertEquals("m() { return 1; }", str("class C { m() { return 1; } } C.prototype.m.toString()"));
        assertEquals("m() {}", str("class C { static m() {} } C.m.toString()"));
        assertEquals("get v() { return 2; }", str("class C { static get v() { return 2; } }"
                + " Object.getOwnPropertyDescriptor(C, 'v').get.toString()"));
        assertEquals("#p() {}", str("class C { #p() {} read() { return this.#p.toString(); } } new C().read()"));
    }

    // a class constructor reports the whole class, implicit constructor and all
    @Test
    public void test_class_source() {
        assertEquals("class A /* a */ { /* b */ }", str("class A /* a */ { /* b */ } A.toString()"));
        assertEquals("class B extends A { constructor() { super(); } }",
                str("class A {} class B extends A { constructor() { super(); } } B.toString()"));
        assertEquals("class { m() {} }", str("const C = class { m() {} }; C.toString()"));
    }

    // a builtin, a bound function and a proxy have no source of their own: the NativeFunction form
    @Test
    public void test_sourceless_callables_keep_the_native_form() {
        assertEquals("function map() { [native code] }", str("Array.prototype.map.toString()"));
        assertEquals("function () { [native code] }", str("function f() {} f.bind(null).toString()"));
        assertEquals("function () { [native code] }", str("function f() {} new Proxy(f, {}).toString()"));
        assertEquals("function f() { [native code] }", str("function f() {} '' + new Proxy(f, {})"));
        assertEquals("function C() { [native code] }", str("class C {} '' + new Proxy(C, {})"));
    }

    // the token-list parse entry point carries no source, so its functions fall back too
    @Test
    public void test_token_only_parse_has_no_source() {
        final var program = Parser.parse(Lexer.lex("function f() { return 1; } f.toString()"));
        assertEquals("function f() { [native code] }", ((JsString) Interpreter.run(program)).getValue());
    }
}
