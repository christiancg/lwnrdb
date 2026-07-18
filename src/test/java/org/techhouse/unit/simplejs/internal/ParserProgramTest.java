package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;

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
}
