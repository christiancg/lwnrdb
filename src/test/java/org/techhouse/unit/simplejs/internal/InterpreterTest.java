package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class InterpreterTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Numeric, boolean, null, undefined and bigint literals evaluate to their values
    @Test
    public void test_literals() {
        assertEquals(42, num("42"));
        assertEquals("hi", str("'hi'"));
        assertTrue(bool("true"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("undefined"));
        assertInstanceOf(org.techhouse.simplejs.values.JsNull.class, Interpreter.run("null"));
        assertEquals(BigInteger.valueOf(9), ((JsBigInt) Interpreter.run("9n")).getValue());
    }

    // Template literals interleave quasis with stringified expressions
    @Test
    public void test_template_literal() {
        assertEquals("sum=3", str("`sum=${1 + 2}`"));
        assertEquals("1,2,3", str("`${[1, 2, 3]}`"));
    }

    // An empty program evaluates to undefined
    @Test
    public void test_empty_program() {
        assertInstanceOf(JsUndefined.class, Interpreter.run(""));
    }

    // Arithmetic honours operator precedence
    @Test
    public void test_arithmetic_precedence() {
        assertEquals(14, num("2 + 3 * 4"));
        assertEquals(20, num("(2 + 3) * 4"));
        assertEquals(2, num("2 ** 3 % 6"));
    }

    // Logical operators short-circuit and return operand values
    @Test
    public void test_logical_operators() {
        assertEquals(5, num("0 || 5"));
        assertEquals(2, num("1 && 2"));
        assertEquals(3, num("null ?? 3"));
        assertEquals(0, num("0 ?? 3"));
    }

    // The conditional operator selects a branch by the truthiness of its test
    @Test
    public void test_conditional() {
        assertEquals(1, num("true ? 1 : 2"));
        assertEquals(2, num("'' ? 1 : 2"));
    }

    // typeof reports value kinds and treats undeclared identifiers as undefined
    @Test
    public void test_typeof() {
        assertEquals("number", str("typeof 1"));
        assertEquals("string", str("typeof 'a'"));
        assertEquals("bigint", str("typeof 1n"));
        assertEquals("object", str("typeof null"));
        assertEquals("undefined", str("typeof notDeclared"));
    }

    // var declarations hoist to the surrounding function scope
    @Test
    public void test_var_hoisting_through_block() {
        assertEquals(1, num("{ var x = 1; } x"));
    }

    // let and const are block scoped and do not leak
    @Test
    public void test_lexical_block_scope() {
        assertEquals(9, num("let x = 9; { let x = 1; } x"));
        assertThrows(ReferenceErrorException.class, () -> Interpreter.run("{ let y = 1; } y"));
    }

    // Assignment supports plain, compound and logical forms
    @Test
    public void test_assignment_forms() {
        assertEquals(8, num("let x = 5; x += 3; x"));
        assertEquals(8, num("let x = 2; x **= 3; x"));
        assertEquals(7, num("let x = 0; x ||= 7; x"));
        assertEquals(9, num("let x = 1; x &&= 9; x"));
    }

    // Prefix and postfix update expressions return the right value and mutate the binding
    @Test
    public void test_update_expressions() {
        assertEquals(6, num("let x = 5; ++x"));
        assertEquals(5, num("let x = 5; x++"));
        assertEquals(6, num("let x = 5; x++; x"));
        assertEquals(4, num("let x = 5; --x"));
    }

    // Objects support dot, bracket, computed and shorthand properties
    @Test
    public void test_object_member_access() {
        assertEquals(1, num("let o = { a: 1, b: 2 }; o.a"));
        assertEquals(2, num("let o = { a: 1, b: 2 }; o['b']"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = {}; o.missing"));
        assertEquals(5, num("let k = 'x'; let o = { [k]: 5 }; o.x"));
        assertEquals(1, num("let a = 1; let o = { a }; o.a"));
    }

    // Arrays support indexing, length and element assignment
    @Test
    public void test_array_member_access() {
        assertEquals(3, num("[1, 2, 3].length"));
        assertEquals(1, num("[1, 2, 3][0]"));
        assertEquals(2, ((JsArray) Interpreter.run("[1, 2]")).length());
        assertEquals(9, num("let a = [1, 2]; a[0] = 9; a[0]"));
    }

    // String indexing and length work as intrinsic property reads
    @Test
    public void test_string_member_access() {
        assertEquals(3, num("'abc'.length"));
        assertEquals("b", str("'abc'[1]"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("'abc'[9]"));
    }

    // Optional chaining short-circuits on a nullish receiver
    @Test
    public void test_optional_chaining() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = null; o?.a"));
        assertEquals(1, num("let o = { a: 1 }; o?.a"));
    }

    // delete removes an object property and the in operator tests membership
    @Test
    public void test_delete_and_in() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let o = { a: 1 }; delete o.a; o.a"));
        assertTrue(bool("'a' in { a: 1 }"));
        assertFalse(bool("'b' in { a: 1 }"));
        assertTrue(bool("0 in [1]"));
        assertFalse(bool("2 in [1]"));
    }

    // if/else selects the matching branch
    @Test
    public void test_if_else() {
        assertEquals(1, num("let r = 0; if (true) { r = 1; } else { r = 2; } r"));
        assertEquals(2, num("let r = 0; if (false) { r = 1; } else { r = 2; } r"));
    }

    // while and do-while loops accumulate until their test fails
    @Test
    public void test_while_loops() {
        assertEquals(10, num("let i = 0; let s = 0; while (i < 5) { s += i; i++; } s"));
        assertEquals(3, num("let i = 0; let s = 0; do { s += i; i++; } while (i < 3); s"));
    }

    // A C-style for loop sums a range
    @Test
    public void test_for_loop() {
        assertEquals(10, num("let s = 0; for (let i = 0; i < 5; i++) { s += i; } s"));
    }

    // break and continue control the nearest loop
    @Test
    public void test_break_and_continue() {
        assertEquals(6, num("let s = 0; for (let i = 0; i < 10; i++) { if (i === 4) break; s += i; } s"));
        assertEquals(20, num("let s = 0; for (let i = 0; i < 5; i++) { if (i % 2 === 0) continue; s += i; } s + 16"));
    }

    // Labeled break and continue target an outer loop
    @Test
    public void test_labeled_loops() {
        assertEquals(1, num(
                "let r = 0; outer: for (let i = 0; i < 3; i++) { for (let j = 0; j < 3; j++) { if (j === 1) break outer; r++; } } r"));
        assertEquals(3, num(
                "let r = 0; outer: for (let i = 0; i < 3; i++) { for (let j = 0; j < 3; j++) { if (j === 1) continue outer; r++; } r += 10; } r"));
    }

    // Reading a nullish value's property without optional chaining throws TypeError
    @Test
    public void test_nullish_member_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let o = null; o.a"));
    }

    // A top-level break or return is an illegal syntax error
    @Test
    public void test_illegal_control_flow() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("break;"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("return 5;"));
    }

    // Nodes outside the phase 6a scope raise UnsupportedNodeException
    @Test
    public void test_unsupported_nodes() {
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("function f() {}"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("f()"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("try { } catch (e) { }"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("[...a]"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("let [a] = [1];"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("1 instanceof Object"));
        assertThrows(UnsupportedNodeException.class, () -> Interpreter.run("switch (1) { case 1: break; }"));
    }

    // this evaluates to undefined at the top level in phase 6a
    @Test
    public void test_this_is_undefined() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("this"));
    }

    // Object and array literals build the corresponding value kinds
    @Test
    public void test_literal_containers() {
        assertInstanceOf(JsObject.class, Interpreter.run("({ a: 1 })"));
        assertInstanceOf(JsArray.class, Interpreter.run("[1, 2]"));
    }

    // Compound and logical assignment apply to member targets
    @Test
    public void test_member_compound_and_logical_assignment() {
        assertEquals(5, num("let o = { a: 1 }; o.a += 4; o.a"));
        assertEquals(7, num("let o = { a: 0 }; o.a ||= 7; o.a"));
        assertEquals(9, num("let o = { a: 1 }; o.a &&= 9; o.a"));
        assertEquals(3, num("let o = { a: null }; o.a ??= 3; o.a"));
    }

    // Logical assignment keeps the current value when short-circuited
    @Test
    public void test_logical_assignment_keep_branch() {
        assertEquals(0, num("let x = 0; x &&= 9; x"));
        assertEquals(1, num("let x = 1; x ||= 7; x"));
        assertEquals(2, num("let x = 2; x ??= 9; x"));
        assertEquals(0, num("let o = { a: 0 }; o.a &&= 9; o.a"));
    }

    // Update expressions mutate member targets and BigInt bindings
    @Test
    public void test_update_member_and_bigint() {
        assertEquals(6, num("let o = { a: 5 }; o.a++; o.a"));
        assertEquals(6, num("let o = { a: 5 }; ++o.a"));
        assertEquals(BigInteger.valueOf(2), ((JsBigInt) Interpreter.run("let x = 1n; x++; x")).getValue());
        assertEquals(BigInteger.valueOf(5), ((JsBigInt) Interpreter.run("let x = 5n; x++")).getValue());
    }

    // delete clears an array slot, leaving undefined behind
    @Test
    public void test_delete_array_element() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let a = [1, 2, 3]; delete a[1]; a[1]"));
    }

    // Object literals accept string and numeric static keys
    @Test
    public void test_static_property_keys() {
        assertEquals(1, num("let o = { 'k': 1 }; o.k"));
        assertEquals(9, num("let o = { 5: 9 }; o[5]"));
    }

    // Array holes read back as undefined without shifting length
    @Test
    public void test_array_holes() {
        assertEquals(3, num("[1, , 3].length"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("[1, , 3][1]"));
    }

    // The in operator handles array length and rejects non-object right-hand sides
    @Test
    public void test_in_edge_cases() {
        assertTrue(bool("'length' in [1]"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("1 in 5"));
    }

    // Property reads on primitives yield undefined, writes to nullish throw
    @Test
    public void test_primitive_and_nullish_member() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("let n = 5; n.foo"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let o = null; o.a = 1;"));
    }

    // A for loop accepts an expression initializer and omitted clauses
    @Test
    public void test_for_loop_clause_variants() {
        assertEquals(3, num("let s = 0; let i; for (i = 0; ; i++) { if (i >= 3) break; s += i; } s"));
        assertEquals(3, num("let s = 0; for (; s < 3;) { s++; } s"));
    }

    // A labeled block is exited by a break that names it
    @Test
    public void test_labeled_block_break() {
        assertEquals(1, num("let r = 0; blk: { r = 1; break blk; r = 2; } r"));
    }
}
