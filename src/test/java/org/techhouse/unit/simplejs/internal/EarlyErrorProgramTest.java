package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;

public class EarlyErrorProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static void rejects(String source) {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run(source));
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
}
