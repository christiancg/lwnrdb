package org.techhouse.simplejs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.internal.Lexer;

public record ModuleReferences(Set<String> procedureNames, boolean opaque) {
    public static final String PROCEDURE_SPECIFIER_PREFIX = "procedures/";
    public static final String SCRIPT_MODULE = "script";
    private static final String IMPORT = "import";

    public static ModuleReferences scan(String source, List<String> staticSpecifiers) {
        final var names = new LinkedHashSet<String>();
        var opaque = false;
        for (final var specifier : staticSpecifiers) {
            opaque |= recordSpecifier(specifier, names);
        }
        opaque |= scanDynamicImports(Lexer.lex(source), names);
        return new ModuleReferences(Set.copyOf(names), opaque);
    }

    public static String procedureNameOf(String specifier) {
        if (specifier == null || !specifier.startsWith(PROCEDURE_SPECIFIER_PREFIX)) {
            return null;
        }
        final var name = specifier.substring(PROCEDURE_SPECIFIER_PREFIX.length());
        return name.isEmpty() || name.indexOf('/') >= 0 ? null : name;
    }

    private static boolean recordSpecifier(String specifier, Set<String> names) {
        final var name = procedureNameOf(specifier);
        if (name != null) {
            names.add(name);
        }
        return SCRIPT_MODULE.equals(specifier);
    }

    private static boolean scanDynamicImports(List<JsBaseElement> tokens, Set<String> names) {
        var opaque = false;
        for (var i = 0; i < tokens.size(); i++) {
            final var token = tokens.get(i);
            if (token instanceof JsTemplateString template) {
                for (final var expression : template.getExpressions()) {
                    opaque |= scanDynamicImports(expression, names);
                }
            } else if (isImportCall(tokens, i)) {
                opaque |= !literalSpecifierAt(tokens, i + 2, names);
            }
        }
        return opaque;
    }

    private static boolean isImportCall(List<JsBaseElement> tokens, int index) {
        return tokens.get(index) instanceof JsKeyword keyword && IMPORT.equals(keyword.getValue())
                && index + 1 < tokens.size() && isSeparator(tokens.get(index + 1), '(')
                && !(index > 0 && isMemberAccess(tokens.get(index - 1)));
    }

    private static boolean isMemberAccess(JsBaseElement token) {
        return token instanceof JsOperator operator
                && (".".equals(operator.getValue()) || "?.".equals(operator.getValue()));
    }

    private static boolean literalSpecifierAt(List<JsBaseElement> tokens, int index, Set<String> names) {
        if (index + 1 >= tokens.size() || !(tokens.get(index) instanceof JsString literal)) {
            return false;
        }
        final var next = tokens.get(index + 1);
        if (!isSeparator(next, ')') && !isSeparator(next, ',')) {
            return false;
        }
        return !recordSpecifier(literal.getValue(), names);
    }

    private static boolean isSeparator(JsBaseElement token, char value) {
        return token instanceof JsSeparator separator && separator.getValue() == value;
    }
}
