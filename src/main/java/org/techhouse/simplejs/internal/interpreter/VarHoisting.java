package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;

// Spec VarScopedDeclarations: a `var` belongs to the enclosing function (or script) scope no matter
// how deeply it is nested in blocks, loops, `try` or `switch`, and its binding exists from entry
// even when the statement holding it never runs. Recursion stops at a function or class boundary,
// which starts its own variable scope.
public final class VarHoisting {
    private VarHoisting() {
    }

    public static void hoistVars(List<Statement> body, Environment env) {
        for (final var name : varNames(body)) {
            env.declareVar(name);
        }
    }

    private static List<String> varNames(List<Statement> body) {
        final var names = new ArrayList<String>();
        for (final var statement : body) {
            collect(statement, names);
        }
        return names;
    }

    private static void collect(JsNode node, List<String> names) {
        switch (node) {
            case ExportNamedDeclaration export -> collect(export.getDeclaration(), names);
            case VariableDeclaration declaration -> {
                if ("var".equals(declaration.getKind())) {
                    for (final var declarator : declaration.getDeclarations()) {
                        InterpreterUtils.collectBoundNames(declarator.getId(), names);
                    }
                }
            }
            case BlockStatement block -> collectAll(block.getBody(), names);
            case IfStatement statement -> {
                collect(statement.getConsequent(), names);
                collect(statement.getAlternate(), names);
            }
            case WhileStatement statement -> collect(statement.getBody(), names);
            case DoWhileStatement statement -> collect(statement.getBody(), names);
            case ForStatement statement -> {
                collect(statement.getInit(), names);
                collect(statement.getBody(), names);
            }
            case ForInStatement statement -> {
                collect(statement.getLeft(), names);
                collect(statement.getBody(), names);
            }
            case ForOfStatement statement -> {
                collect(statement.getLeft(), names);
                collect(statement.getBody(), names);
            }
            case LabeledStatement statement -> collect(statement.getBody(), names);
            case TryStatement statement -> {
                collect(statement.getBlock(), names);
                if (statement.getHandler() != null) {
                    collect(statement.getHandler().getBody(), names);
                }
                collect(statement.getFinalizer(), names);
            }
            case SwitchStatement statement -> {
                for (final var switchCase : statement.getCases()) {
                    collectAll(switchCase.getConsequent(), names);
                }
            }
            case null, default -> {
            }
        }
    }

    private static void collectAll(List<Statement> body, List<String> names) {
        for (final var statement : body) {
            collect(statement, names);
        }
    }
}
