package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;

public class ParserClassSyntaxTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    private static Expression firstExpression(String source) {
        return ((ExpressionStatement) firstStatement(source)).getExpression();
    }

    // The expression in the body of a class declaration's last method: a private name only parses
    // inside the class that declares it.
    private static Expression privateBodyExpression(String source) {
        final var klass = (ClassDeclaration) firstStatement(source);
        final var method = (MethodDefinition) klass.getBody().getMembers().getLast();
        final var body = method.getValue().getBody();
        return ((ExpressionStatement) body.getBody().getFirst()).getExpression();
    }

    @Test
    public void test_object_method_shorthand() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ foo() {}, [k]() {} })"));
        final var method = assertInstanceOf(Property.class, obj.getProperties().getFirst());
        assertEquals("method", method.getKind());
        assertInstanceOf(FunctionExpression.class, method.getValue());
        final var computed = assertInstanceOf(Property.class, obj.getProperties().get(1));
        assertEquals("method", computed.getKind());
        assertTrue(computed.isComputed());
    }

    @Test
    public void test_object_accessors() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ get x() {}, set x(v) {} })"));
        assertEquals("get", assertInstanceOf(Property.class, obj.getProperties().getFirst()).getKind());
        assertEquals("set", assertInstanceOf(Property.class, obj.getProperties().get(1)).getKind());
    }

    // `true`/`false`/`null`/`undefined` are ordinary IdentifierNames grammar-wise even though the
    // lexer turns them into value tokens rather than keywords, so each is a legal accessor name too.
    @Test
    public void test_object_accessors_named_with_literal_keywords() {
        final var obj = assertInstanceOf(ObjectExpression.class,
                firstExpression("({ get undefined() {}, set undefined(v) {}, get null() {}, set true(v) {} })"));
        final var getUndefined = assertInstanceOf(Property.class, obj.getProperties().getFirst());
        assertEquals("get", getUndefined.getKind());
        assertEquals("undefined", ((Identifier) getUndefined.getKey()).getName());
        final var setUndefined = assertInstanceOf(Property.class, obj.getProperties().get(1));
        assertEquals("set", setUndefined.getKind());
        assertEquals("undefined", ((Identifier) setUndefined.getKey()).getName());
        final var getNull = assertInstanceOf(Property.class, obj.getProperties().get(2));
        assertEquals("get", getNull.getKind());
        assertEquals("null", ((Identifier) getNull.getKey()).getName());
        final var setTrue = assertInstanceOf(Property.class, obj.getProperties().get(3));
        assertEquals("set", setTrue.getKind());
        assertEquals("true", ((Identifier) setTrue.getKey()).getName());
    }

    // a classic for head takes an ordinary using declaration list, initializers and all
    @Test
    public void test_using_in_classic_for_head() {
        final var statement = assertInstanceOf(ForStatement.class, firstStatement("for (using i = a, j = b;;) {}"));
        final var declaration = assertInstanceOf(VariableDeclaration.class, statement.getInit());
        assertEquals("using", declaration.getKind());
        assertEquals(2, declaration.getDeclarations().size());
    }

    // An empty class declaration has a name, no superclass and no members
    @Test
    public void test_empty_class() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C {}"));
        assertEquals("C", decl.getId().getName());
        assertNull(decl.getSuperClass());
        assertTrue(decl.getBody().getMembers().isEmpty());
    }

    // extends with a plain identifier heritage
    @Test
    public void test_class_extends() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C extends B {}"));
        final var superClass = assertInstanceOf(Identifier.class, decl.getSuperClass());
        assertEquals("B", superClass.getName());
    }

    // extends accepts a member expression heritage
    @Test
    public void test_class_extends_member() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C extends a.B {}"));
        assertInstanceOf(MemberExpression.class, decl.getSuperClass());
    }

    // A plain method is a non-static method with a function value
    @Test
    public void test_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("m", ((Identifier) method.getKey()).getName());
        assertEquals("method", method.getKind());
        assertFalse(method.isStatic());
        assertInstanceOf(FunctionExpression.class, method.getValue());
    }

    // A constructor member resolves to the constructor kind
    @Test
    public void test_class_constructor() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { constructor(x) {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("constructor", method.getKind());
        assertEquals(1, method.getValue().getParams().size());
    }

    // A static method carries the static flag
    @Test
    public void test_static_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isStatic());
        assertEquals("method", method.getKind());
    }

    // A computed method key sets the computed flag
    @Test
    public void test_computed_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { [a + b]() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isComputed());
        assertInstanceOf(BinaryExpression.class, method.getKey());
    }

    // A class field with an initializer
    @Test
    public void test_class_field() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { x = 1; }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("x", ((Identifier) field.getKey()).getName());
        assertInstanceOf(NumberLiteral.class, field.getValue());
        assertFalse(field.isStatic());
    }

    // A class field without an initializer has a null value
    @Test
    public void test_class_field_no_init() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { x }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertNull(field.getValue());
    }

    // A static field carries the static flag
    @Test
    public void test_static_field() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static x = 1; }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(field.isStatic());
        assertInstanceOf(NumberLiteral.class, field.getValue());
    }

    // A private field carries a PrivateIdentifier key
    @Test
    public void test_class_private_field() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { #x = 1; }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("x", ((PrivateIdentifier) field.getKey()).getName());
        assertInstanceOf(NumberLiteral.class, field.getValue());
    }

    // A private method carries a PrivateIdentifier key
    @Test
    public void test_class_private_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { #m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("m", ((PrivateIdentifier) method.getKey()).getName());
    }

    // A static initialization block parses to a StaticBlock member
    @Test
    public void test_static_block() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static { x = 1; } }"));
        final var block = assertInstanceOf(StaticBlock.class, decl.getBody().getMembers().getFirst());
        assertEquals(1, block.getBody().size());
    }

    // An empty static block is valid
    @Test
    public void test_static_block_empty() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static {} }"));
        final var block = assertInstanceOf(StaticBlock.class, decl.getBody().getMembers().getFirst());
        assertTrue(block.getBody().isEmpty());
    }

    // Private member access via this.#x resolves the property to a PrivateIdentifier
    @Test
    public void test_private_member_access() {
        final var member = assertInstanceOf(MemberExpression.class,
                privateBodyExpression("class C { #x; m() { this.#x } }"));
        assertInstanceOf(ThisExpression.class, member.getObject());
        assertEquals("x", ((PrivateIdentifier) member.getProperty()).getName());
    }

    // A #x in obj brand check parses to a BinaryExpression with a PrivateIdentifier left side
    @Test
    public void test_private_in_expression() {
        final var binary = assertInstanceOf(BinaryExpression.class,
                privateBodyExpression("class C { #x; m() { #x in obj } }"));
        assertEquals("in", binary.getOperator());
        assertEquals("x", ((PrivateIdentifier) binary.getLeft()).getName());
    }

    // A member literally named "static" is a method, not a static modifier
    @Test
    public void test_member_named_static() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertFalse(method.isStatic());
        assertEquals("static", ((Identifier) method.getKey()).getName());
    }

    // An anonymous class expression has a null id
    @Test
    public void test_class_expression_anonymous() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const C = class {};"));
        final var expr = assertInstanceOf(ClassExpression.class, decl.getDeclarations().getFirst().getInit());
        assertNull(expr.getId());
    }

    // A named class expression carries its name
    @Test
    public void test_class_expression_named() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const C = class Named {};"));
        final var expr = assertInstanceOf(ClassExpression.class, decl.getDeclarations().getFirst().getInit());
        assertEquals("Named", expr.getId().getName());
    }

    // super(...) parses to a call over a super expression, inside a derived constructor
    @Test
    public void test_super_call() {
        final var declaration = assertInstanceOf(ClassDeclaration.class,
                firstStatement("class C extends B { constructor() { super(x); } }"));
        final var method = assertInstanceOf(MethodDefinition.class, declaration.getBody().getMembers().getFirst());
        final var statement = method.getValue().getBody().getBody().getFirst();
        final var call = assertInstanceOf(CallExpression.class, ((ExpressionStatement) statement).getExpression());
        assertInstanceOf(SuperExpression.class, call.getCallee());
    }

    // a super call outside a derived constructor is an early error
    @Test
    public void test_super_call_outside_derived_constructor() {
        assertThrows(SyntaxErrorException.class, () -> parse("super(x);"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C extends B { m() { super(); } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { constructor() { super(); } }"));
    }

    // super.m() parses to a call over a member access on super; a super property only reaches a method
    @Test
    public void test_super_member() {
        final var call = assertInstanceOf(CallExpression.class,
                privateBodyExpression("class C extends B { m() { super.m(); } }"));
        final var member = assertInstanceOf(MemberExpression.class, call.getCallee());
        assertInstanceOf(SuperExpression.class, member.getObject());
        assertThrows(SyntaxErrorException.class, () -> parse("super.m()"));
    }

    // A class declaration requires a name
    @Test
    public void test_class_declaration_requires_name() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class {}"));
    }

    // An unterminated class body reports end of input
    @Test
    public void test_unterminated_class_body() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("class C {"));
    }

    // A getter cannot be a field
    @Test
    public void test_getter_cannot_be_field() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { get x = 1 }"));
    }

    // async class method sets async on its function value
    @Test
    public void test_async_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isAsync());
        assertFalse(method.getValue().isGenerator());
        assertEquals("method", method.getKind());
    }

    // generator class method sets generator on its function value
    @Test
    public void test_generator_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { *m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isGenerator());
    }

    // async generator class method sets both flags
    @Test
    public void test_async_generator_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async *m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isAsync());
        assertTrue(method.getValue().isGenerator());
    }

    // static async method carries the static flag and async on its value
    @Test
    public void test_static_async_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static async m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isStatic());
        assertTrue(method.getValue().isAsync());
    }

    // A field literally named "async" is a field, not a modifier
    @Test
    public void test_field_named_async() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async = 1 }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("async", ((Identifier) field.getKey()).getName());
    }
}
