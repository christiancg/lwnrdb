package org.techhouse.simplejs.internal.parser;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ExportSpecifier;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.ImportAttribute;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;

abstract class ModuleProductions extends StatementProductions {
    protected ModuleProductions(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore, boolean strictScriptGoal) {
        super(source, tokens, positions, newlineBefore, strictScriptGoal);
    }

    @Override
    protected ImportDeclaration parseImportDeclaration() {
        rejectInScriptGoal("An import declaration");
        expectKeyword("import");
        if (current().getType() == JsType.STRING) {
            final var source = parseModuleSource();
            final var attributes = parseImportAttributes();
            consumeSemicolon();
            return new ImportDeclaration(List.of(), source, attributes);
        }
        final var specifiers = new ArrayList<JsNode>();
        if (matchOperator("*")) {
            specifiers.add(parseImportNamespaceSpecifier());
        } else if (isSeparator('{')) {
            parseNamedImportSpecifiers(specifiers);
        } else {
            specifiers.add(new ImportDefaultSpecifier(parseBindingIdentifier()));
            if (matchSeparator(',')) {
                if (matchOperator("*")) {
                    specifiers.add(parseImportNamespaceSpecifier());
                } else {
                    parseNamedImportSpecifiers(specifiers);
                }
            }
        }
        expectContextualKeyword("from");
        final var source = parseModuleSource();
        final var attributes = parseImportAttributes();
        consumeSemicolon();
        return new ImportDeclaration(specifiers, source, attributes);
    }

    protected List<ImportAttribute> parseImportAttributes() {
        if (!matchContextualKeyword("with")) {
            return List.of();
        }
        expectSeparator('{');
        final var attributes = new ArrayList<ImportAttribute>();
        while (!isSeparator('}')) {
            final var key = parseModuleExportName();
            expectOperator(":");
            attributes.add(new ImportAttribute(key, parseModuleSource()));
            if (!isSeparator('}')) {
                expectSeparator(',');
            }
        }
        expectSeparator('}');
        return attributes;
    }

    protected ImportNamespaceSpecifier parseImportNamespaceSpecifier() {
        expectContextualKeyword("as");
        return new ImportNamespaceSpecifier(parseBindingIdentifier());
    }

    protected void parseNamedImportSpecifiers(List<JsNode> specifiers) {
        expectSeparator('{');
        while (!isSeparator('}')) {
            final var imported = parseModuleExportName();
            final Identifier local;
            if (matchContextualKeyword("as")) {
                local = parseBindingIdentifier();
            } else {
                local = asBindingIdentifier(imported);
                validateBindingName(local.getName());
            }
            specifiers.add(new ImportSpecifier(imported, local));
            if (!isSeparator('}')) {
                expectSeparator(',');
            }
        }
        expectSeparator('}');
    }

    @Override
    protected Statement parseExportDeclaration() {
        rejectInScriptGoal("An export declaration");
        expectKeyword("export");
        if (matchKeyword("default")) {
            final var declaration = parseAssignment();
            consumeSemicolon();
            return new ExportDefaultDeclaration(declaration);
        }
        if (matchOperator("*")) {
            return parseExportAll();
        }
        if (isSeparator('{')) {
            return parseExportNamed();
        }
        return new ExportNamedDeclaration(parseExportedDeclaration(), List.of(), null, List.of());
    }

    protected ExportAllDeclaration parseExportAll() {
        Identifier exported = null;
        if (matchContextualKeyword("as")) {
            exported = asBindingIdentifier(parseModuleExportName());
        }
        expectContextualKeyword("from");
        final var source = parseModuleSource();
        final var attributes = parseImportAttributes();
        consumeSemicolon();
        return new ExportAllDeclaration(exported, source, attributes);
    }

    protected ExportNamedDeclaration parseExportNamed() {
        expectSeparator('{');
        final var specifiers = new ArrayList<ExportSpecifier>();
        while (!isSeparator('}')) {
            final var local = parseModuleExportName();
            final var exported = matchContextualKeyword("as") ? parseModuleExportName() : local;
            specifiers.add(new ExportSpecifier(local, exported));
            if (!isSeparator('}')) {
                expectSeparator(',');
            }
        }
        expectSeparator('}');
        StringLiteral source = null;
        var attributes = List.<ImportAttribute>of();
        if (matchContextualKeyword("from")) {
            source = parseModuleSource();
            attributes = parseImportAttributes();
        }
        consumeSemicolon();
        return new ExportNamedDeclaration(null, specifiers, source, attributes);
    }

    protected Statement parseExportedDeclaration() {
        if (isKeyword("var") || isKeyword("let") || isKeyword("const")) {
            return parseVariableDeclaration();
        }
        if (isKeyword("function")) {
            return parseFunctionDeclaration(false, spanStart());
        }
        if (isKeyword("async") && peek().getType() == JsType.KEYWORD
                && "function".equals(((JsKeyword) peek()).getValue())) {
            final var start = spanStart();
            advance();
            return parseFunctionDeclaration(true, start);
        }
        if (isKeyword("class")) {
            return parseClassDeclaration();
        }
        throw error();
    }

    protected StringLiteral parseModuleSource() {
        final var t = current();
        if (t.getType() != JsType.STRING) {
            throw error();
        }
        advance();
        return new StringLiteral(((JsString) t).getValue());
    }

    protected Expression parseModuleExportName() {
        final var t = current();
        final Expression name = switch (t.getType()) {
            case IDENTIFIER -> new Identifier(((JsIdentifier) t).getValue());
            case KEYWORD -> new Identifier(((JsKeyword) t).getValue());
            case STRING -> new StringLiteral(((JsString) t).getValue());
            default -> throw error();
        };
        advance();
        return name;
    }

    protected Identifier asBindingIdentifier(Expression name) {
        if (name instanceof Identifier id) {
            return id;
        }
        throw error();
    }
}
