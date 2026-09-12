package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.StaticBlock;

public class ParserModuleProgramTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    @Test
    public void test_module_imports_and_exports() {
        final var source = """
                import defaults, { a, b as c } from "lib";
                import * as ns from "other";
                function helper() {}
                class Widget {}
                export { helper, Widget as Thing };
                """;
        final var body = parse(source).getBody();
        assertEquals(5, body.size());
        final var first = assertInstanceOf(ImportDeclaration.class, body.getFirst());
        assertEquals(3, first.getSpecifiers().size());
        assertEquals("lib", first.getSource().getValue());
        final var second = assertInstanceOf(ImportDeclaration.class, body.get(1));
        assertEquals("other", second.getSource().getValue());
        assertInstanceOf(FunctionDeclaration.class, body.get(2));
        assertInstanceOf(ClassDeclaration.class, body.get(3));
        final var export = assertInstanceOf(ExportNamedDeclaration.class, body.get(4));
        assertEquals(2, export.getSpecifiers().size());
        assertNull(export.getSource());
    }

    @Test
    public void test_module_reexports_and_default() {
        final var source = """
                export { x } from "a";
                export * from "b";
                export default function () {}
                """;
        final var body = parse(source).getBody();
        assertEquals(3, body.size());
        final var named = assertInstanceOf(ExportNamedDeclaration.class, body.getFirst());
        assertEquals("a", named.getSource().getValue());
        final var all = assertInstanceOf(ExportAllDeclaration.class, body.get(1));
        assertEquals("b", all.getSource().getValue());
        assertInstanceOf(ExportDefaultDeclaration.class, body.get(2));
    }

    @Test
    public void test_phase5f_private_and_static_block_program() {
        final var source = """
                class Counter {
                    #count = 0;
                    static #instances = 0;
                    static {
                        Counter.#instances = 0;
                    }
                    increment() {
                        this.#count++;
                        return this.#count;
                    }
                }
                """;
        final var decl = assertInstanceOf(ClassDeclaration.class, parse(source).getBody().getFirst());
        final var members = decl.getBody().getMembers();
        assertEquals(4, members.size());
        assertEquals("count", ((PrivateIdentifier) ((FieldDefinition) members.get(0)).getKey()).getName());
        assertTrue(((FieldDefinition) members.get(1)).isStatic());
        assertInstanceOf(StaticBlock.class, members.get(2));
        assertInstanceOf(MethodDefinition.class, members.get(3));
    }

    // The static-block flag rejects `return` and `arguments`, so leaking it made both an early error for the
    // rest of the enclosing scope. Only reachable under the relaxed script goal, so test262 cannot see it.
    @Test
    public void test_static_block_does_not_leak_return_restriction() {
        final var source = """
                class Counter {
                    static created = 0;
                    static { Counter.created = 1; }
                }
                return Counter.created;
                """;
        final var body = parse(source).getBody();
        assertEquals(2, body.size());
        assertInstanceOf(ClassDeclaration.class, body.getFirst());
        assertInstanceOf(ReturnStatement.class, body.get(1));
    }

    @Test
    public void test_static_block_does_not_leak_arguments_restriction() {
        final var source = """
                class Holder {
                    static { Holder.ready = true; }
                }
                function reader() {
                    return arguments.length;
                }
                return reader(1, 2);
                """;
        final var body = parse(source).getBody();
        assertEquals(3, body.size());
        assertInstanceOf(ReturnStatement.class, body.get(2));
    }

    @Test
    public void test_return_inside_a_static_block_is_still_an_early_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("class A { static { return 1; } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class A { static { arguments; } }"));
    }
}
