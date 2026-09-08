package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.USING_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.matchesLabel;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.List;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.SwitchCase;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.values.JsUndefined;

public final class StatementEvaluator {
    private final Interpreter interp;

    final LoopEvaluator loops;

    private final ForInEnumeration forIn;

    public StatementEvaluator(Interpreter interp, MemberEvaluator members, ProxyDispatch proxies) {
        this.interp = interp;
        this.forIn = new ForInEnumeration(interp, proxies, this);
        this.loops = new LoopEvaluator(interp, members, this);
    }

    public Completion evalBlock(BlockStatement block, Environment env) {
        final var blockEnv = env.child();
        interp.hoist(block.getBody(), blockEnv);
        if (!blockDeclaresUsing(block.getBody())) {
            return execStatements(block.getBody(), blockEnv);
        }
        return runDisposing(blockEnv, () -> execStatements(block.getBody(), blockEnv));
    }

    public Completion execStatements(List<Statement> body, Environment env) {
        for (final var statement : body) {
            final var completion = interp.evalStatement(statement, env);
            if (!completion.isNormal()) {
                return completion;
            }
        }
        return Completion.empty();
    }

    public boolean blockDeclaresUsing(List<Statement> body) {
        for (final var statement : body) {
            if (statement instanceof VariableDeclaration declaration && USING_KINDS.contains(declaration.getKind())) {
                return true;
            }
        }
        return false;
    }

    public Completion runDisposing(Environment env, Supplier<Completion> body) {
        var result = Completion.empty();
        RuntimeException pending = null;
        try {
            result = body.get();
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            pending = error;
        }
        return disposeScope(env, result, pending);
    }

    public Completion disposeScope(Environment env, Completion result, RuntimeException pending) {
        if (!env.hasDisposables()) {
            if (pending != null) {
                throw pending;
            }
            return result;
        }
        final var entries = env.disposables();
        var error = pending;
        for (var i = entries.size() - 1; i >= 0; i--) {
            final var entry = entries.get(i);
            try {
                final var outcome = isCallable(entry.method())
                        ? interp.callValue(entry.method(), entry.resource(), List.of())
                        : JsUndefined.getInstance();
                if (entry.async()) {
                    interp.currentCoroutine().await(interp.toPromise(outcome));
                }
            } catch (ScriptAbortException abort) {
                throw abort;
            } catch (RuntimeException disposeError) {
                error = error == null
                        ? disposeError
                        : new JsThrowException(ErrorBuiltins.makeSuppressedError(
                                toErrorValue(disposeError, interp.intrinsics()),
                                toErrorValue(error, interp.intrinsics()), "An error was suppressed during disposal"));
            }
        }
        if (error != null) {
            throw error;
        }
        return result;
    }

    public Completion evalIf(IfStatement statement, Environment env) {
        if (JsCoercion.toBoolean(interp.eval(statement.getTest(), env))) {
            return interp.evalStatement(statement.getConsequent(), env);
        }
        if (statement.getAlternate() != null) {
            return interp.evalStatement(statement.getAlternate(), env);
        }
        return Completion.empty();
    }

    public Completion evalLabeled(LabeledStatement statement, Environment env) {
        final var label = statement.getLabel().getName();
        final var body = statement.getBody();
        final var completion = switch (body.getType()) {
            case WHILE_STATEMENT -> loops.evalWhile((WhileStatement) body, env, label);
            case DO_WHILE_STATEMENT -> loops.evalDoWhile((DoWhileStatement) body, env, label);
            case FOR_STATEMENT -> loops.evalFor((ForStatement) body, env, label);
            case FOR_IN_STATEMENT -> forIn.evalForIn((ForInStatement) body, env, label);
            case FOR_OF_STATEMENT -> loops.evalForOf((ForOfStatement) body, env, label);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) body, env, label);
            default -> interp.evalStatement(body, env);
        };
        if (completion.kind() == Completion.Kind.BREAK && label.equals(completion.label())) {
            return Completion.empty();
        }
        return completion;
    }

    public Completion evalReturn(ReturnStatement statement, Environment env) {
        final var argument = statement.getArgument();
        if (argument == null) {
            return Completion.returnValue(JsUndefined.getInstance());
        }
        final var value = interp.eval(argument, env);
        final var coroutine = interp.currentCoroutine();
        if (coroutine != null && coroutine.isAsync() && coroutine.isYieldAllowed()) {
            return Completion.returnValue(coroutine.await(interp.toPromise(value)));
        }
        return Completion.returnValue(value);
    }

    public Completion evalTry(TryStatement statement, Environment env) {
        var result = Completion.empty();
        RuntimeException pending = null;
        try {
            try {
                result = evalBlock(statement.getBlock(), env);
            } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                    | SyntaxErrorException error) {
                if (statement.getHandler() == null) {
                    throw error;
                }
                result = interp.evalCatch(statement.getHandler(), toErrorValue(error, interp.intrinsics()), env);
            }
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            pending = error;
        }
        if (statement.getFinalizer() != null) {
            final var finalizer = evalBlock(statement.getFinalizer(), env);
            if (!finalizer.isNormal()) {
                return finalizer;
            }
        }
        if (pending != null) {
            throw pending;
        }
        return result;
    }

    public Completion evalSwitch(SwitchStatement statement, Environment env, String label) {
        final var discriminant = interp.eval(statement.getDiscriminant(), env);
        final var switchEnv = env.child();
        for (final var switchCase : statement.getCases()) {
            interp.hoist(switchCase.getConsequent(), switchEnv);
        }
        final var cases = statement.getCases();
        var start = -1;
        var defaultIndex = -1;
        for (var i = 0; i < cases.size(); i++) {
            final var test = cases.get(i).getTest();
            if (test == null) {
                defaultIndex = i;
            } else if (JsOperators.strictEquals(discriminant, interp.eval(test, switchEnv))) {
                start = i;
                break;
            }
        }
        if (start == -1) {
            start = defaultIndex;
        }
        if (start == -1) {
            return Completion.empty();
        }
        final var begin = start;
        return runDisposing(switchEnv, () -> execSwitchCases(cases, begin, switchEnv, label));
    }

    private Completion execSwitchCases(List<SwitchCase> cases, int start, Environment switchEnv, String label) {
        for (var i = start; i < cases.size(); i++) {
            for (final var consequent : cases.get(i).getConsequent()) {
                final var completion = interp.evalStatement(consequent, switchEnv);
                if (completion.kind() == Completion.Kind.BREAK && matchesLabel(completion.label(), label)) {
                    return Completion.empty();
                }
                if (!completion.isNormal()) {
                    return completion;
                }
            }
        }
        return Completion.empty();
    }
    public Completion evalWhile(WhileStatement statement, Environment env, String label) {
        return loops.evalWhile(statement, env, label);
    }

    public Completion evalDoWhile(DoWhileStatement statement, Environment env, String label) {
        return loops.evalDoWhile(statement, env, label);
    }

    public Completion evalFor(ForStatement statement, Environment env, String label) {
        return loops.evalFor(statement, env, label);
    }

    public Completion evalForOf(ForOfStatement statement, Environment env, String label) {
        return loops.evalForOf(statement, env, label);
    }

    public Completion evalForIn(ForInStatement statement, Environment env, String label) {
        return forIn.evalForIn(statement, env, label);
    }
}
