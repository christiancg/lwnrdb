package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;

public class ParserProgramTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    // A function with a for loop and a return parses to the expected top-level shape
    @Test
    public void test_function_with_for_loop_and_return() {
        final var source = """
                function sum(n) {
                    let total = 0;
                    for (let i = 0; i < n; i++) {
                        total += i;
                    }
                    return total;
                }
                """;
        final var program = parse(source);
        assertEquals(1, program.getBody().size());
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        assertEquals("sum", fn.getName().getName());
        final var body = fn.getBody().getBody();
        assertEquals(3, body.size());
        assertInstanceOf(VariableDeclaration.class, body.get(0));
        assertInstanceOf(ForStatement.class, body.get(1));
        assertInstanceOf(ReturnStatement.class, body.get(2));
    }

    // An arrow used as a callback in a method call parses end-to-end
    @Test
    public void test_arrow_callback_in_call() {
        final var program = parse("const doubled = items.map(x => x * 2);");
        final var decl = assertInstanceOf(VariableDeclaration.class, program.getBody().getFirst());
        final var call = assertInstanceOf(CallExpression.class, decl.getDeclarations().getFirst().getInit());
        assertInstanceOf(MemberExpression.class, call.getCallee());
        assertInstanceOf(ArrowFunctionExpression.class, call.getArguments().getFirst());
    }

    // Nested objects and arrays parse into the expected containers
    @Test
    public void test_nested_objects_and_arrays() {
        final var program = parse("const data = { items: [1, 2, { k: [true, null] }], name: \"x\" };");
        final var decl = assertInstanceOf(VariableDeclaration.class, program.getBody().getFirst());
        assertInstanceOf(ObjectExpression.class, decl.getDeclarations().getFirst().getInit());
    }

    // A template literal driving a member/call chain parses end-to-end
    @Test
    public void test_template_driving_chain() {
        final var program = parse("`Hello ${user.name}, you have ${count} messages`.toUpperCase();");
        final var stmt = assertInstanceOf(ExpressionStatement.class, program.getBody().getFirst());
        final var call = assertInstanceOf(CallExpression.class, stmt.getExpression());
        final var member = assertInstanceOf(MemberExpression.class, call.getCallee());
        final var tpl = assertInstanceOf(TemplateLiteral.class, member.getObject());
        assertEquals(2, tpl.getExpressions().size());
    }

    // A multi-statement program with mixed constructs keeps the right statement count
    @Test
    public void test_mixed_program() {
        final var source = """
                let x = 10;
                if (x > 5) {
                    x = x - 1;
                } else {
                    x = 0;
                }
                while (x > 0) {
                    x--;
                }
                """;
        final var program = parse(source);
        assertEquals(3, program.getBody().size());
        assertInstanceOf(BlockStatement.class, ((IfStatement) program.getBody().get(1)).getConsequent());
        assertInstanceOf(WhileStatement.class, program.getBody().get(2));
    }

    // A for-of accumulation loop inside a function parses to the expected shape
    @Test
    public void test_function_with_for_of_loop() {
        final var source = """
                function total(items) {
                    let sum = 0;
                    for (const item of items) {
                        sum += item;
                    }
                    return sum;
                }
                """;
        final var program = parse(source);
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        final var body = fn.getBody().getBody();
        assertEquals(3, body.size());
        assertInstanceOf(ForOfStatement.class, body.get(1));
    }

    // A try/catch/finally wrapping a throw parses end-to-end
    @Test
    public void test_try_catch_finally_with_throw() {
        final var source = """
                function run() {
                    try {
                        throw new Error("bad");
                    } catch (e) {
                        return e;
                    } finally {
                        cleanup();
                    }
                }
                """;
        final var program = parse(source);
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        final var tryStatement = assertInstanceOf(TryStatement.class, fn.getBody().getBody().getFirst());
        assertInstanceOf(ThrowStatement.class, tryStatement.getBlock().getBody().getFirst());
        assertEquals("e", assertInstanceOf(Identifier.class, tryStatement.getHandler().getParam()).getName());
        assertInstanceOf(BlockStatement.class, tryStatement.getFinalizer());
    }

    // A switch with multiple cases and a default parses to the expected shape
    @Test
    public void test_switch_with_multiple_cases() {
        final var source = """
                switch (kind) {
                    case 1:
                        a();
                        break;
                    case 2:
                        b();
                        break;
                    default:
                        c();
                }
                """;
        final var switchStatement = assertInstanceOf(SwitchStatement.class, parse(source).getBody().getFirst());
        assertEquals(3, switchStatement.getCases().size());
        assertNull(switchStatement.getCases().get(2).getTest());
    }

    // A class with extends, a super-calling constructor, a static method, a getter and a field
    @Test
    public void test_full_class() {
        final var source = """
                class Point extends Base {
                    count = 0;
                    constructor(x, y) {
                        super(x);
                        this.y = y;
                    }
                    static origin() {
                        return new Point(0, 0);
                    }
                    get first() {
                        return this.x;
                    }
                }
                """;
        final var clazz = assertInstanceOf(ClassDeclaration.class, parse(source).getBody().getFirst());
        assertEquals("Point", clazz.getId().getName());
        assertEquals("Base", ((Identifier) clazz.getSuperClass()).getName());
        final var members = clazz.getBody().getMembers();
        assertEquals(4, members.size());
        assertInstanceOf(FieldDefinition.class, members.get(0));
        assertEquals("constructor", ((MethodDefinition) members.get(1)).getKind());
        assertTrue(((MethodDefinition) members.get(2)).isStatic());
        assertEquals("get", ((MethodDefinition) members.get(3)).getKind());
    }

    // An async function awaiting calls inside a try/catch parses end-to-end
    @Test
    public void test_async_function_with_await_in_try() {
        final var source = """
                async function load(url) {
                    try {
                        const res = await fetch(url);
                        return await res.json();
                    } catch (e) {
                        return null;
                    }
                }
                """;
        final var program = parse(source);
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        assertTrue(fn.isAsync());
        final var tryStatement = assertInstanceOf(TryStatement.class, fn.getBody().getBody().getFirst());
        final var decl = assertInstanceOf(VariableDeclaration.class, tryStatement.getBlock().getBody().getFirst());
        assertInstanceOf(AwaitExpression.class, decl.getDeclarations().getFirst().getInit());
        final var ret = assertInstanceOf(ReturnStatement.class, tryStatement.getBlock().getBody().get(1));
        assertInstanceOf(AwaitExpression.class, ret.getArgument());
    }

    // A generator yielding in a loop with a trailing yield* delegation parses end-to-end
    @Test
    public void test_generator_with_yield_in_loop() {
        final var source = """
                function* walk(items) {
                    for (const item of items) {
                        yield item;
                    }
                    yield* rest;
                }
                """;
        final var program = parse(source);
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        assertTrue(fn.isGenerator());
        final var body = fn.getBody().getBody();
        final var loop = assertInstanceOf(ForOfStatement.class, body.getFirst());
        final var loopBody = assertInstanceOf(BlockStatement.class, loop.getBody());
        final var yieldStmt = assertInstanceOf(ExpressionStatement.class, loopBody.getBody().getFirst());
        assertFalse(assertInstanceOf(YieldExpression.class, yieldStmt.getExpression()).isDelegate());
        final var delegateStmt = assertInstanceOf(ExpressionStatement.class, body.get(1));
        assertTrue(assertInstanceOf(YieldExpression.class, delegateStmt.getExpression()).isDelegate());
    }

    // A class mixing plain, async, generator, static async generator and getter members
    @Test
    public void test_class_with_async_and_generator_members() {
        final var source = """
                class Service {
                    plain() {}
                    async fetchData() {
                        return await this.load();
                    }
                    *items() {
                        yield 1;
                    }
                    static async *stream() {
                        yield* source;
                    }
                    get ready() {
                        return true;
                    }
                }
                """;
        final var clazz = assertInstanceOf(ClassDeclaration.class, parse(source).getBody().getFirst());
        final var members = clazz.getBody().getMembers();
        assertEquals(5, members.size());
        final var plain = (MethodDefinition) members.getFirst();
        assertFalse(plain.getValue().isAsync());
        assertFalse(plain.getValue().isGenerator());
        assertTrue(((MethodDefinition) members.get(1)).getValue().isAsync());
        assertTrue(((MethodDefinition) members.get(2)).getValue().isGenerator());
        final var asyncGen = (MethodDefinition) members.get(3);
        assertTrue(asyncGen.isStatic());
        assertTrue(asyncGen.getValue().isAsync());
        assertTrue(asyncGen.getValue().isGenerator());
        assertEquals("get", ((MethodDefinition) members.get(4)).getKind());
    }

    // Spread and rest appear together across a realistic function
    @Test
    public void test_spread_and_rest_program() {
        final var source = """
                function merge(first, ...others) {
                    const all = [first, ...others];
                    return combine(...all);
                }
                """;
        final var program = parse(source);
        assertEquals(1, program.getBody().size());
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        assertEquals(2, fn.getParams().size());
        assertInstanceOf(RestElement.class, fn.getParams().get(1));
        final var body = fn.getBody().getBody();
        final var decl = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        final var array = assertInstanceOf(ArrayExpression.class, decl.getDeclarations().getFirst().getInit());
        assertEquals(2, array.getElements().size());
        assertInstanceOf(SpreadElement.class, array.getElements().get(1));
        final var ret = assertInstanceOf(ReturnStatement.class, body.get(1));
        final var call = assertInstanceOf(CallExpression.class, ret.getArgument());
        assertInstanceOf(SpreadElement.class, call.getArguments().getFirst());
    }

    // Destructuring appears across a declaration, a defaulted pattern parameter, and an assignment
    @Test
    public void test_destructuring_program() {
        final var source = """
                function unpack({ id, tags = [] }, [first, ...others]) {
                    const { name: label } = lookup(id);
                    [first] = others;
                    return label;
                }
                """;
        final var program = parse(source);
        assertEquals(1, program.getBody().size());
        final var fn = assertInstanceOf(FunctionDeclaration.class, program.getBody().getFirst());
        assertEquals(2, fn.getParams().size());
        final var objectParam = assertInstanceOf(ObjectPattern.class, fn.getParams().getFirst());
        assertInstanceOf(AssignmentPattern.class,
                assertInstanceOf(Property.class, objectParam.getProperties().get(1)).getValue());
        final var arrayParam = assertInstanceOf(ArrayPattern.class, fn.getParams().get(1));
        assertInstanceOf(RestElement.class, arrayParam.getElements().get(1));
        final var body = fn.getBody().getBody();
        final var decl = assertInstanceOf(VariableDeclaration.class, body.getFirst());
        final var declPattern = assertInstanceOf(ObjectPattern.class, decl.getDeclarations().getFirst().getId());
        assertEquals("label", assertInstanceOf(Identifier.class,
                assertInstanceOf(Property.class, declPattern.getProperties().getFirst()).getValue()).getName());
        final var assignStatement = assertInstanceOf(ExpressionStatement.class, body.get(1));
        final var assign = assertInstanceOf(AssignmentExpression.class, assignStatement.getExpression());
        assertInstanceOf(ArrayPattern.class, assign.getTarget());
    }
}
