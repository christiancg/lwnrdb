package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ExportSpecifier;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.VariableDeclaration;

public class ParserModuleSyntaxTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    // A bare import has no specifiers, only a source
    @Test
    public void test_import_bare() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import \"mod\";"));
        assertTrue(decl.getSpecifiers().isEmpty());
        assertEquals("mod", decl.getSource().getValue());
    }

    // A default import binds a single local name
    @Test
    public void test_import_default() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def from \"mod\";"));
        assertEquals(1, decl.getSpecifiers().size());
        final var spec = assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("def", spec.getLocal().getName());
        assertEquals("mod", decl.getSource().getValue());
    }

    // An import with attributes carries them; without a with clause the list is empty
    @Test
    public void test_import_with_attributes() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import data from \"data.json\" with { type: \"json\" };"));
        assertEquals(1, decl.getAttributes().size());
        final var attr = decl.getAttributes().getFirst();
        assertEquals("type", ((Identifier) attr.getKey()).getName());
        assertEquals("json", attr.getValue().getValue());
    }

    @Test
    public void test_import_without_attributes_is_empty() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def from \"mod\";"));
        assertTrue(decl.getAttributes().isEmpty());
    }

    // A bare side-effect import may also carry attributes
    @Test
    public void test_bare_import_with_attributes() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import \"mod\" with { type: \"json\", lazy: \"true\" };"));
        assertEquals(2, decl.getAttributes().size());
    }

    // Wildcard and named re-exports carry attributes too
    @Test
    public void test_export_all_with_attributes() {
        final var decl = assertInstanceOf(ExportAllDeclaration.class,
                firstStatement("export * from \"mod\" with { type: \"json\" };"));
        assertEquals(1, decl.getAttributes().size());
    }

    @Test
    public void test_export_named_reexport_with_attributes() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class,
                firstStatement("export { a } from \"mod\" with { type: \"json\" };"));
        assertEquals(1, decl.getAttributes().size());
    }

    // A non-string attribute value is a parse error
    @Test
    public void test_import_attribute_non_string_value_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import x from \"m\" with { type: json };"));
    }

    // `with` is contextual in the sense that matters - it is not a lexer keyword, so an import
    // clause can use it - but it is still a reserved word, so it cannot be a binding identifier
    // (the engine is always strict).
    @Test
    public void test_with_is_contextual_in_an_import_clause_only() {
        assertInstanceOf(ImportDeclaration.class, firstStatement("import x from \"m\" with { type: \"json\" };"));
        assertThrows(SyntaxErrorException.class, () -> parse("let with = 1;"));
    }

    // A namespace import binds `* as name`
    @Test
    public void test_import_namespace() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import * as ns from \"mod\";"));
        final var spec = assertInstanceOf(ImportNamespaceSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("ns", spec.getLocal().getName());
    }

    // Named imports carry imported and local names, aliased via `as`
    @Test
    public void test_import_named() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { a, b as c } from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        final var first = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("a", assertInstanceOf(Identifier.class, first.getImported()).getName());
        assertEquals("a", first.getLocal().getName());
        final var second = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().get(1));
        assertEquals("b", assertInstanceOf(Identifier.class, second.getImported()).getName());
        assertEquals("c", second.getLocal().getName());
    }

    // A keyword may name an imported binding (`default as x`)
    @Test
    public void test_import_named_keyword_name() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { default as x } from \"mod\";"));
        final var spec = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("default", assertInstanceOf(Identifier.class, spec.getImported()).getName());
        assertEquals("x", spec.getLocal().getName());
    }

    // A string may name an imported binding (`"a" as x`)
    @Test
    public void test_import_named_string_name() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { \"a\" as x } from \"mod\";"));
        final var spec = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("a", assertInstanceOf(StringLiteral.class, spec.getImported()).getValue());
        assertEquals("x", spec.getLocal().getName());
    }

    // A default import combines with a named group
    @Test
    public void test_import_default_plus_named() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def, { a } from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().get(1));
    }

    // A default import combines with a namespace import
    @Test
    public void test_import_default_plus_namespace() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def, * as ns from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertInstanceOf(ImportNamespaceSpecifier.class, decl.getSpecifiers().get(1));
    }

    // Empty braces import nothing but still require a source
    @Test
    public void test_import_empty_braces() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import {} from \"mod\";"));
        assertTrue(decl.getSpecifiers().isEmpty());
        assertEquals("mod", decl.getSource().getValue());
    }

    // A trailing comma inside the named-import braces is allowed
    @Test
    public void test_import_named_trailing_comma() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import { a, } from \"mod\";"));
        assertEquals(1, decl.getSpecifiers().size());
    }

    // Named exports carry local and exported names, aliased via `as`
    @Test
    public void test_export_named() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a, b as c };"));
        assertNull(decl.getDeclaration());
        assertNull(decl.getSource());
        assertEquals(2, decl.getSpecifiers().size());
        final var first = decl.getSpecifiers().getFirst();
        assertEquals("a", assertInstanceOf(Identifier.class, first.getLocal()).getName());
        assertEquals("a", assertInstanceOf(Identifier.class, first.getExported()).getName());
        final var second = decl.getSpecifiers().get(1);
        assertEquals("b", assertInstanceOf(Identifier.class, second.getLocal()).getName());
        assertEquals("c", assertInstanceOf(Identifier.class, second.getExported()).getName());
    }

    // A named export may re-export from another module
    @Test
    public void test_export_named_reexport() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a } from \"mod\";"));
        assertEquals("mod", decl.getSource().getValue());
    }

    // A string may name an exported binding (`a as "x"`)
    @Test
    public void test_export_named_string_name() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a as \"x\" };"));
        final ExportSpecifier spec = decl.getSpecifiers().getFirst();
        assertEquals("x", assertInstanceOf(StringLiteral.class, spec.getExported()).getValue());
    }

    // export * re-exports everything with no local name
    @Test
    public void test_export_all() {
        final var decl = assertInstanceOf(ExportAllDeclaration.class, firstStatement("export * from \"mod\";"));
        assertNull(decl.getExported());
        assertEquals("mod", decl.getSource().getValue());
    }

    // export * as ns names the re-exported namespace
    @Test
    public void test_export_all_as() {
        final var decl = assertInstanceOf(ExportAllDeclaration.class, firstStatement("export * as ns from \"mod\";"));
        assertEquals("ns", decl.getExported().getName());
    }

    // A default export wraps an arbitrary expression
    @Test
    public void test_export_default_expression() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class, firstStatement("export default 1 + 2;"));
        assertInstanceOf(BinaryExpression.class, decl.getDeclaration());
    }

    // A default export accepts a function value
    @Test
    public void test_export_default_function() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class,
                firstStatement("export default function f() {}"));
        assertInstanceOf(FunctionExpression.class, decl.getDeclaration());
    }

    // A default export accepts an anonymous class value
    @Test
    public void test_export_default_class() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class, firstStatement("export default class {}"));
        assertInstanceOf(ClassExpression.class, decl.getDeclaration());
    }

    // Exporting a var declaration keeps the declaration and no specifiers
    @Test
    public void test_export_var_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export const x = 1;"));
        assertInstanceOf(VariableDeclaration.class, decl.getDeclaration());
        assertTrue(decl.getSpecifiers().isEmpty());
        assertNull(decl.getSource());
    }

    // Exporting a function declaration keeps the declaration
    @Test
    public void test_export_function_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export function f() {}"));
        assertInstanceOf(FunctionDeclaration.class, decl.getDeclaration());
    }

    // Exporting an async function declaration preserves the async flag
    @Test
    public void test_export_async_function_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export async function f() {}"));
        final var fn = assertInstanceOf(FunctionDeclaration.class, decl.getDeclaration());
        assertTrue(fn.isAsync());
    }

    // Exporting a class declaration keeps the declaration
    @Test
    public void test_export_class_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export class C {}"));
        assertInstanceOf(ClassDeclaration.class, decl.getDeclaration());
    }

    // Empty export braces are allowed
    @Test
    public void test_export_empty_braces() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export {};"));
        assertTrue(decl.getSpecifiers().isEmpty());
    }

    // A named import without a `from` clause is a parse error
    @Test
    public void test_import_missing_from_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import { a } 42;"));
    }

    // A named import that ends before its source is an unexpected end of input
    @Test
    public void test_import_missing_source_eof_throws() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("import { a }"));
    }

    // A non-string import source is a parse error
    @Test
    public void test_import_non_string_source_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import def from 123;"));
    }

    // A namespace import without `as` is a parse error
    @Test
    public void test_import_namespace_missing_as_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import * ns from \"mod\";"));
    }

    // A dangling comma clause after a default import is a parse error
    @Test
    public void test_import_dangling_clause_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import def, ;"));
    }

    // export * without a source is a parse error
    @Test
    public void test_export_all_missing_from_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("export * ;"));
    }

    // export followed by a non-declaration is a parse error
    @Test
    public void test_export_bad_declaration_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("export 123;"));
    }

    // export at end of input is an unexpected end of input
    @Test
    public void test_export_eof_throws() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("export"));
    }
}
