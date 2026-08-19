package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

// Spec NamedEvaluation/SetFunctionName: an anonymous function or class expression takes the name of
// whatever binding it is being assigned to.
public class FunctionNameInferenceTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // A declarator's identifier names an anonymous function/arrow/class/generator initializer
    @Test
    public void test_variable_declarator_names_initializer() {
        assertEquals("fn", str("var fn = function(){}; fn.name"));
        assertEquals("arrow", str("const arrow = () => {}; arrow.name"));
        assertEquals("cls", str("let cls = class {}; cls.name"));
        assertEquals("gen", str("var gen = function*(){}; gen.name"));
    }

    // A named function expression keeps its own name, it is not overwritten by the binding
    @Test
    public void test_named_function_expression_keeps_its_name() {
        assertEquals("inner", str("var outer = function inner(){}; outer.name"));
    }

    // Plain and logical assignment to an identifier name their anonymous right-hand side
    @Test
    public void test_assignment_names_value() {
        assertEquals("a", str("var a; a = function(){}; a.name"));
        assertEquals("b", str("var b; b ||= () => {}; b.name"));
    }

    // A parenthesized left-hand side is a CoverParenthesizedExpression, not a bare IdentifierRef, so
    // NamedEvaluation must NOT apply even though the parser resolves the same Identifier target
    // (language/expressions/assignment/fn-name-lhs-cover.js)
    @Test
    public void test_parenthesized_assignment_target_suppresses_naming() {
        assertEquals("", str("var fn; (fn) = function(){}; fn.name"));
        assertEquals("", str("var fn; (fn) ||= function(){}; fn.name"));
        assertEquals("fn", str("var fn; fn = function(){}; fn.name"));
    }

    // Object-literal shorthand methods, accessors and plain properties are all named
    @Test
    public void test_object_literal_members_are_named() {
        assertEquals("m", str("({ m(){} }).m.name"));
        assertEquals("x", str("({ x: function(){} }).x.name"));
        assertEquals("get g", str("Object.getOwnPropertyDescriptor({ get g(){ return 1; } }, 'g').get.name"));
        assertEquals("set s", str("Object.getOwnPropertyDescriptor({ set s(v){} }, 's').set.name"));
    }

    // Class methods, accessors, statics and fields are named the same way
    @Test
    public void test_class_members_are_named() {
        assertEquals("m", str("class C { m(){} } C.prototype.m.name"));
        assertEquals("s", str("class C { static s(){} } C.s.name"));
        assertEquals("get g", str(
                "class C { get g(){ return 1; } }" + " Object.getOwnPropertyDescriptor(C.prototype, 'g').get.name"));
        assertEquals("f", str("class C { f = () => {}; } new C().f.name"));
        assertEquals("sf", str("class C { static sf = function(){}; } C.sf.name"));
    }

    // A symbol-keyed member takes the "[description]" form
    @Test
    public void test_symbol_keyed_member_name() {
        assertEquals("[Symbol.iterator]", str("({ [Symbol.iterator](){} })[Symbol.iterator].name"));
    }

    // Destructuring defaults name their initializer through the binding identifier - the single
    // AssignmentPattern choke point covers declarations, parameters and assignment alike
    @Test
    public void test_destructuring_defaults_are_named() {
        assertEquals("x", str("var { x = function(){} } = {}; x.name"));
        assertEquals("y", str("var [ y = class {} ] = []; y.name"));
        assertEquals("p", str("function f(p = () => {}) { return p.name; } f()"));
        assertEquals("q", str("var q; ({ q = function(){} } = {}); q.name"));
    }

    // IsAnonymousFunctionDefinition is an AST-level test: a parenthesized function still qualifies,
    // but a comma expression that merely evaluates to one does not
    @Test
    public void test_cover_grammar_is_not_named() {
        assertEquals("cover", str("var cover = (function(){}); cover.name"));
        assertEquals("", str("var notCovered = (0, function(){}); notCovered.name"));
    }

    // A class field with no initializer must not be treated as a named evaluation site
    @Test
    public void test_field_without_initializer() {
        assertEquals("undefined", str("class C { x; } String(new C().x)"));
    }
}
