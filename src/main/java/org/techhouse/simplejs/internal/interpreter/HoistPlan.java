package org.techhouse.simplejs.internal.interpreter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.VariableDeclaration;

public final class HoistPlan {
    private static final Set<String> LEXICAL_KINDS = Set.of("let", "const");
    private static final Set<String> USING_KINDS = Set.of("using", "await using");

    public sealed interface Action {
        record DeclareLexical(String name, String kind) implements Action {
        }

        record DeclareVar(String name) implements Action {
        }

        record DeclareFunction(FunctionDeclaration declaration) implements Action {
        }
    }

    private final List<Action> actions;
    private final boolean declaresUsing;

    private HoistPlan(List<Action> actions, boolean declaresUsing) {
        this.actions = actions;
        this.declaresUsing = declaresUsing;
    }

    public List<Action> actions() {
        return actions;
    }

    public boolean declaresUsing() {
        return declaresUsing;
    }

    public static HoistPlan of(List<Statement> body, BoundNameCollector collector) {
        final var actions = new ArrayList<Action>();
        var declaresUsing = false;
        for (final var raw : body) {
            final var statement = raw instanceof ExportNamedDeclaration export
                    && export.getDeclaration() instanceof Statement inner ? inner : raw;
            if (statement instanceof VariableDeclaration declaration) {
                final var kind = declaration.getKind();
                for (final var declarator : declaration.getDeclarations()) {
                    final var names = new ArrayList<String>();
                    collector.collect(declarator.getId(), names);
                    for (final var name : names) {
                        if (LEXICAL_KINDS.contains(kind)) {
                            actions.add(new Action.DeclareLexical(name, kind));
                        } else if (USING_KINDS.contains(kind)) {
                            actions.add(new Action.DeclareLexical(name, "const"));
                        } else if ("var".equals(kind)) {
                            actions.add(new Action.DeclareVar(name));
                        }
                    }
                }
            } else if (statement instanceof FunctionDeclaration declaration) {
                actions.add(new Action.DeclareFunction(declaration));
            }
        }
        for (final var statement : body) {
            if (statement instanceof VariableDeclaration declaration && USING_KINDS.contains(declaration.getKind())) {
                declaresUsing = true;
                break;
            }
        }
        return new HoistPlan(List.copyOf(actions), declaresUsing);
    }

    @FunctionalInterface
    public interface BoundNameCollector {
        void collect(JsNode pattern, List<String> into);
    }
}
