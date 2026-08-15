package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class EarlyErrorProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static void rejects(String source) {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run(source));
    }

    // the parser reports a positional early error as UnexpectedTokenException, a named rule as
    // SyntaxErrorException; both are parse-time rejections
    private static void rejectsParse(String source) {
        final var error = assertThrows(RuntimeException.class, () -> Interpreter.run(source));
        assertTrue(error instanceof SyntaxErrorException || error instanceof UnexpectedTokenException,
                "expected a parse rejection but got " + error);
    }

    // a const declarator must have an initializer
    @Test
    public void test_const_without_initializer_is_a_syntax_error() {
        rejects("const x;");
        rejects("const a = 1, b;");
        rejects("for (const i;;) {}");
    }

    // a let name declared twice in one block clashes
    @Test
    public void test_let_redeclared_in_same_block_is_a_syntax_error() {
        rejects("let a; let a;");
        rejects("{ let a; const a = 1; }");
        rejects("class A {} class A {}");
    }

    // a lexical name and a var name in the same scope clash in either order
    @Test
    public void test_let_and_var_clash_is_a_syntax_error() {
        rejects("let a; var a;");
        rejects("var a; let a;");
        rejects("{ var f; let f }");
        rejects("function x() { { let f; var f; } }");
    }

    // a function declaration inside a block is lexical, so it clashes there
    @Test
    public void test_block_function_declaration_is_lexical() {
        rejects("{ let f; function f() {} }");
        rejects("{ function f() {} function f() {} }");
        rejects("{ function f() {} var f }");
    }

    // shadowing across block and function boundaries stays legal
    @Test
    public void test_let_in_nested_block_shadows_legally() {
        assertEquals(1, num("let a; { let a; } 1"));
        assertEquals(1, num("{ let a; } { let a; } 1"));
        assertEquals(1, num("function f(){ var a; } let a; 1"));
        assertEquals(5, num("function f(a) { { let a = 5; return a } } f(3)"));
        assertEquals(1, num("for (let i = 0; i < 1; i++) { let i = 9; } 1"));
    }

    // every case clause of a switch shares one lexical scope
    @Test
    public void test_switch_case_redeclaration_is_a_syntax_error() {
        rejects("switch (0) { case 1: let f; default: let f }");
        assertEquals(1, num("switch (0) { case 1: { let a; } default: { let a; } } 1"));
    }

    // a parameter and a body-level lexical declaration of the same name clash
    @Test
    public void test_function_parameter_and_let_clash_is_a_syntax_error() {
        rejects("function f(a) { let a; }");
        rejects("(a) => { const a = 1; }");
        assertEquals(3, num("function f(a) { var a; return a } f(3)"));
    }

    // repeated var declarations and top-level function redeclarations stay legal
    @Test
    public void test_var_redeclared_is_legal() {
        assertEquals(2, num("var x = 1; var x = 2; x"));
        assertEquals(1, num("function f(){} function f(){} 1"));
        assertEquals(1, num("var f; function f(){} 1"));
    }

    // a regex literal is validated (compiled) at parse time, before any statement runs
    @Test
    public void test_regex_literal_invalid_pattern_is_a_syntax_error() {
        rejects("/\\P{ASCII=F}/u;");
        rejects("var ran = false; /(/u; ran = true;");
    }

    // an instance field named `constructor`, or a static field named `prototype`, clashes
    @Test
    public void test_field_named_constructor_is_a_syntax_error() {
        rejects("class A { constructor = 1; }");
        rejects("class A { 'constructor' = 1; }");
    }

    @Test
    public void test_static_field_named_prototype_is_a_syntax_error() {
        rejects("class A { static prototype = 1; }");
        rejects("class A { static 'prototype' = 1; }");
    }

    // a private name may repeat only as one getter and one setter pair
    @Test
    public void test_duplicate_private_name_is_a_syntax_error() {
        rejects("class A { #x; #x; }");
        rejects("class A { #x() {} #x() {} }");
        assertEquals(1, num("class A { get #x(){ return 1; } set #x(v){} m(){ return this.#x; } } new A().m()"));
    }

    // `arguments` in a field initializer is forbidden, even through a nested arrow, but not
    // through a nested ordinary function (which has its own `arguments` binding)
    @Test
    public void test_arguments_in_field_initializer_is_a_syntax_error() {
        rejects("class A { x = arguments; }");
        rejects("class A { x = () => arguments; }");
        rejects("class A { x = () => { var t = () => arguments; } }");
        assertEquals(9, num("class A { x = function(){ return arguments[0]; }; } new A().x(9)"));
    }

    // a bare `super()` call in a field initializer is forbidden; `super.prop` access is fine
    @Test
    public void test_super_call_in_field_initializer_is_a_syntax_error() {
        rejects("class B extends Object { x = super(); }");
        rejects("class B extends Object { x = () => super(); }");
        assertEquals(1, num("class B { m(){ return 1; } } " + "class A extends B { x = super.m ? 1 : 0; } new A().x"));
    }

    // "use strict" in a function body clashes with a non-simple parameter list, across every
    // function-like form
    @Test
    public void test_use_strict_with_non_simple_params_is_a_syntax_error() {
        rejects("function f([a]) { 'use strict'; }");
        rejects("function f(a=1) { 'use strict'; }");
        rejects("function f(...a) { 'use strict'; }");
        rejects("(a=1) => { 'use strict'; };");
        rejects("class A { m([a]) { 'use strict'; } }");
        rejects("class A { static async method([element]) { 'use strict'; } }");
        rejects("function* g([a]) { 'use strict'; }");
        assertEquals(1, num("function f(a) { 'use strict'; return a; } f(1)"));
    }

    // the body of if/else, a loop or a label is a Statement, so a declaration there is an early error
    @Test
    public void test_declaration_in_statement_position_is_a_syntax_error() {
        rejects("if (true) function f() {}");
        rejects("if (true) ; else class C {}");
        rejects("while (false) let x = 1;");
        rejects("do const x = 1; while (false)");
        rejects("for (;;) async function f() {}");
        rejects("for (var a in {}) function* g() {}");
        rejects("l: function f() {}");
        rejects("if (false) l1: l2: function f() {}");
        assertEquals(1, num("if (true) var x = 1; x"));
    }

    // a yield expression belongs to a generator body and nowhere else
    @Test
    public void test_yield_outside_a_generator_is_a_syntax_error() {
        rejects("function f() { yield 1; }");
        rejects("async function f() { yield 1; }");
        rejects("function* g(a = yield) {}");
        rejects("function* g() { return () => yield; }");
        rejects("class C { x = yield; }");
        rejects("for ([a = yield] of [[]]) ;");
        assertEquals(1, num("function* g() { yield 1; } g().next().value"));
        assertEquals(1, num("({ yield: 1 }).yield"));
    }

    // an assignment pattern's rest element is final, and an optional chain is not a valid target
    @Test
    public void test_invalid_assignment_pattern_is_a_syntax_error() {
        rejectsParse("[...a, b] = [];");
        rejectsParse("[...a,] = [];");
        rejectsParse("({...a, b} = {});");
        rejectsParse("({...a,} = {});");
        rejectsParse("for ([...a, b] of [[]]) ;");
        rejectsParse("var o = {}; [o?.a] = [1];");
        rejectsParse("var o = {}; o?.a = 1;");
        assertEquals(1, num("var a = []; [...a] = [1]; a[0]"));
    }
}
