package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LEXICAL_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.USING_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class LoopEvaluator {
    private final Interpreter interp;
    private final MemberEvaluator members;
    private final StatementEvaluator statementEvaluator;

    LoopEvaluator(Interpreter interp, MemberEvaluator members, StatementEvaluator statementEvaluator) {
        this.interp = interp;
        this.members = members;
        this.statementEvaluator = statementEvaluator;
    }

    Completion evalIterationBody(Statement body, Environment iterationEnv) {
        if (iterationEnv.hasDisposables()) {
            return statementEvaluator.runDisposing(iterationEnv, () -> interp.evalStatement(body, iterationEnv));
        }
        return interp.evalStatement(body, iterationEnv);
    }

    Completion evalWhile(WhileStatement statement, Environment env, String label) {
        while (JsCoercion.toBoolean(interp.eval(statement.getTest(), env))) {
            interp.tick();
            final var completion = interp.evalStatement(statement.getBody(), env);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        }
        return Completion.empty();
    }

    Completion evalDoWhile(DoWhileStatement statement, Environment env, String label) {
        do {
            interp.tick();
            final var completion = interp.evalStatement(statement.getBody(), env);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        } while (JsCoercion.toBoolean(interp.eval(statement.getTest(), env)));
        return Completion.empty();
    }

    Completion evalFor(ForStatement statement, Environment env, String label) {
        final var loopEnv = env.child();
        if (statement.getInit() instanceof VariableDeclaration declaration
                && USING_KINDS.contains(declaration.getKind())) {
            return statementEvaluator.runDisposing(loopEnv, () -> runFor(statement, env, loopEnv, label));
        }
        return runFor(statement, env, loopEnv, label);
    }

    Completion runFor(ForStatement statement, Environment env, Environment loopEnv, String label) {
        final var init = statement.getInit();
        final var perIterationNames = new ArrayList<String>();
        var perIterationKind = "let";
        if (init instanceof VariableDeclaration declaration) {
            interp.hoist(List.of(declaration), loopEnv);
            interp.evalVariableDeclaration(declaration, loopEnv);
            if (LEXICAL_KINDS.contains(declaration.getKind())) {
                perIterationKind = declaration.getKind();
                for (final var declarator : declaration.getDeclarations()) {
                    collectBoundNames(declarator.getId(), perIterationNames);
                }
            }
        } else if (init instanceof Expression expression) {
            interp.eval(expression, loopEnv);
        }
        var current = perIterationNames.isEmpty()
                ? loopEnv
                : copyForwardLoopEnv(env, loopEnv, perIterationNames, perIterationKind);
        while (statement.getTest() == null || JsCoercion.toBoolean(interp.eval(statement.getTest(), current))) {
            interp.tick();
            final var completion = interp.evalStatement(statement.getBody(), current);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
            if (!perIterationNames.isEmpty()) {
                current = copyForwardLoopEnv(env, current, perIterationNames, perIterationKind);
            }
            if (statement.getUpdate() != null) {
                interp.eval(statement.getUpdate(), current);
            }
        }
        return Completion.empty();
    }

    Environment copyForwardLoopEnv(Environment parent, Environment source, List<String> names, String kind) {
        final var next = parent.child();
        for (final var name : names) {
            next.declareLexical(name, kind);
            next.initialize(name, source.get(name));
        }
        return next;
    }

    Completion evalForOf(ForOfStatement statement, Environment env, String label) {
        if (statement.isAwait()) {
            return evalForAwaitOf(statement, env, label);
        }
        final var iteration = new Iteration(interp,
                evalIterationSource(statement.getLeft(), statement.getRight(), env));
        var value = iteration.next();
        while (value != null) {
            interp.tick();
            final var completion = runForOfIteration(statement, env, iteration, value);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                iteration.close();
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                iteration.close();
                break;
            }
            value = iteration.next();
        }
        return Completion.empty();
    }

    Completion runForOfIteration(ForOfStatement statement, Environment env, Iteration iteration, JsValue value) {
        try {
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), value, iterationEnv);
            return evalIterationBody(statement.getBody(), iterationEnv);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (Coroutine.ReturnSignal signal) {
            iteration.close();
            throw signal;
        } catch (RuntimeException error) {
            iteration.closeAfterThrow();
            throw error;
        }
    }

    Completion evalForAwaitOf(ForOfStatement statement, Environment env, String label) {
        final var coroutine = interp.currentCoroutine();
        if (coroutine == null || !coroutine.isAsync()) {
            throw new SyntaxErrorException("for await is only valid inside an async function");
        }
        final var source = interp.eval(statement.getRight(), env);
        if (source instanceof JsAsyncGenerator generator) {
            return iterateAsyncGenerator(statement, env, label, coroutine, generator);
        }
        return iterateAsyncIterator(statement, env, label, coroutine, AsyncIteration.open(interp, source));
    }

    Completion iterateAsyncIterator(ForOfStatement statement, Environment env, String label, Coroutine coroutine,
            AsyncIteration iteration) {
        while (true) {
            interp.tick();
            final var step = iteration.step(coroutine, JsUndefined.getInstance());
            if (step.done()) {
                break;
            }
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), step.value(), iterationEnv);
            final var completion = evalIterationBody(statement.getBody(), iterationEnv);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE || action == LoopAction.BREAK_LOOP) {
                iteration.close();
                if (action == LoopAction.PROPAGATE) {
                    return completion;
                }
                break;
            }
        }
        return Completion.empty();
    }

    Completion iterateAsyncGenerator(ForOfStatement statement, Environment env, String label, Coroutine coroutine,
            JsAsyncGenerator generator) {
        while (true) {
            interp.tick();
            final var step = coroutine.await(interp.toPromise(
                    members.driveAsyncGenerator(generator, MemberEvaluator.AsyncStep.NEXT, JsUndefined.getInstance())));
            if (JsCoercion.toBoolean(members.getMember(step, "done"))) {
                break;
            }
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), members.getMember(step, "value"), iterationEnv);
            final var completion = evalIterationBody(statement.getBody(), iterationEnv);
            final var action = LoopAction.of(completion, label);
            if (action == LoopAction.PROPAGATE) {
                members.driveAsyncGenerator(generator, MemberEvaluator.AsyncStep.RETURN, JsUndefined.getInstance());
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                members.driveAsyncGenerator(generator, MemberEvaluator.AsyncStep.RETURN, JsUndefined.getInstance());
                break;
            }
        }
        return Completion.empty();
    }

    JsValue evalIterationSource(JsNode left, Expression right, Environment env) {
        if (!(left instanceof VariableDeclaration declaration)
                || !(LEXICAL_KINDS.contains(declaration.getKind()) || USING_KINDS.contains(declaration.getKind()))) {
            return interp.eval(right, env);
        }
        final var headEnv = env.child();
        final var names = new ArrayList<String>();
        for (final var declarator : declaration.getDeclarations()) {
            collectBoundNames(declarator.getId(), names);
        }
        for (final var name : names) {
            headEnv.declareLexical(name, declaration.getKind());
        }
        return interp.eval(right, headEnv);
    }
}
