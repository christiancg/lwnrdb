package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.LEXICAL_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.USING_KINDS;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.matchesLabel;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.ArrayList;
import java.util.HashSet;
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
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.SwitchCase;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

// Statement execution: blocks and scoped disposal, the loops (while/do-while/for/for-in/for-of and
// for-await), labeled statements, switch, return and try/catch/finally. Loop bodies and tests, and
// the binding of loop/catch targets, route back through the Interpreter seam; iteration uses the
// shared Iteration helper and async stepping the MemberEvaluator.
public final class StatementEvaluator {
    private enum LoopAction {
        CONTINUE_LOOP, BREAK_LOOP, PROPAGATE
    }

    private final Interpreter interp;
    private final MemberEvaluator members;
    private final ProxyDispatch proxies;

    public StatementEvaluator(Interpreter interp, MemberEvaluator members, ProxyDispatch proxies) {
        this.interp = interp;
        this.members = members;
        this.proxies = proxies;
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

    private Completion evalIterationBody(Statement body, Environment iterationEnv) {
        if (iterationEnv.hasDisposables()) {
            return runDisposing(iterationEnv, () -> interp.evalStatement(body, iterationEnv));
        }
        return interp.evalStatement(body, iterationEnv);
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
                // Dispose(V, hint, method): a nullish `using`/`await using` resource still occupies
                // a disposal slot with method == undefined - no call happens, but an `await using`
                // slot still performs the implied Await(undefined), a real microtask tick.
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

    public Completion evalWhile(WhileStatement statement, Environment env, String label) {
        while (JsCoercion.toBoolean(interp.eval(statement.getTest(), env))) {
            interp.tick();
            final var completion = interp.evalStatement(statement.getBody(), env);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        }
        return Completion.empty();
    }

    public Completion evalDoWhile(DoWhileStatement statement, Environment env, String label) {
        do {
            interp.tick();
            final var completion = interp.evalStatement(statement.getBody(), env);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        } while (JsCoercion.toBoolean(interp.eval(statement.getTest(), env)));
        return Completion.empty();
    }

    public Completion evalFor(ForStatement statement, Environment env, String label) {
        final var loopEnv = env.child();
        if (statement.getInit() instanceof VariableDeclaration declaration
                && USING_KINDS.contains(declaration.getKind())) {
            return runDisposing(loopEnv, () -> runFor(statement, env, loopEnv, label));
        }
        return runFor(statement, env, loopEnv, label);
    }

    private Completion runFor(ForStatement statement, Environment env, Environment loopEnv, String label) {
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
            final var action = classify(completion, label);
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

    // CreatePerIterationEnvironment re-declares each bound name with its original declaration kind -
    // a `const` loop variable stays non-writable in every per-iteration copy, so `i++` in the update
    // expression (or anywhere in the body) still throws, matching a single-scope `const` loop.
    private Environment copyForwardLoopEnv(Environment parent, Environment source, List<String> names, String kind) {
        final var next = parent.child();
        for (final var name : names) {
            next.declareLexical(name, kind);
            next.initialize(name, source.get(name));
        }
        return next;
    }

    public Completion evalForOf(ForOfStatement statement, Environment env, String label) {
        if (statement.isAwait()) {
            return evalForAwaitOf(statement, env, label);
        }
        final var iteration = new Iteration(interp,
                evalIterationSource(statement.getLeft(), statement.getRight(), env));
        var value = iteration.next();
        while (value != null) {
            interp.tick();
            final var completion = runForOfIteration(statement, env, iteration, value);
            final var action = classify(completion, label);
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

    // Binding the target or running the body abruptly is a throw completion for the loop's iterator,
    // which is closed with that completion before the error propagates.
    private Completion runForOfIteration(ForOfStatement statement, Environment env, Iteration iteration,
            JsValue value) {
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

    private Completion evalForAwaitOf(ForOfStatement statement, Environment env, String label) {
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

    private Completion iterateAsyncIterator(ForOfStatement statement, Environment env, String label,
            Coroutine coroutine, AsyncIteration iteration) {
        while (true) {
            interp.tick();
            final var step = iteration.step(coroutine, JsUndefined.getInstance());
            if (step.done()) {
                break;
            }
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), step.value(), iterationEnv);
            final var completion = evalIterationBody(statement.getBody(), iterationEnv);
            final var action = classify(completion, label);
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

    private Completion iterateAsyncGenerator(ForOfStatement statement, Environment env, String label,
            Coroutine coroutine, JsAsyncGenerator generator) {
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
            final var action = classify(completion, label);
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

    // ForIn/OfHeadEvaluation: the head's lexically declared names exist, uninitialised, while the
    // source expression runs, so `for (let x of [x])` is a ReferenceError rather than a lookup of an
    // outer `x`. `using`/`await using` are ForDeclaration bindings too (of-only), so they get the
    // same TDZ treatment as `let`/`const`.
    private JsValue evalIterationSource(JsNode left, Expression right, Environment env) {
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

    public Completion evalForIn(ForInStatement statement, Environment env, String label) {
        final var target = evalIterationSource(statement.getLeft(), statement.getRight(), env);
        // A snapshot of target's own keys at enumeration time, so a later re-check only ever asks
        // "does target still have this key" about a key that was target's own to begin with -
        // never about one inherited from a prototype (out of scope here) or re-derived via a
        // different trap (a Proxy's `has`, which need not agree with its `ownKeys`/gOPD pair).
        final var targetOwnKeys = target instanceof JsObject object ? object.keys() : null;
        for (final var key : enumerateKeys(target)) {
            interp.tick();
            // The key list is a snapshot; a key deleted by an earlier iteration's body before its own
            // turn comes up must be skipped rather than re-fetched as undefined.
            if (target instanceof JsObject object && targetOwnKeys.contains(key) && !object.has(key)
                    && !object.hasAccessor(key)) {
                continue;
            }
            final var iterationEnv = env.child();
            interp.bindForTarget(statement.getLeft(), new JsString(key), iterationEnv);
            final var completion = evalIterationBody(statement.getBody(), iterationEnv);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        }
        return Completion.empty();
    }

    private boolean isEnumerableProxyKey(JsProxy proxy, JsValue key) {
        final var descriptor = proxies.getOwnPropertyDescriptor(proxy, key);
        return descriptor instanceof JsObject desc && desc.has("enumerable")
                && JsCoercion.toBoolean(desc.get("enumerable"));
    }

    // EnumerateObjectProperties: own enumerable string keys first, then each prototype's, with a name
    // already seen at a nearer level shadowing the inherited one even when the nearer one is not
    // itself enumerable.
    private List<String> enumerateKeys(JsValue target) {
        final var result = new ArrayList<String>();
        final var seen = new HashSet<String>();
        // Mirrors MemberEvaluator.Chain.advance(): an exotic value with no real [[Prototype]] slot of
        // its own (JsRegExp/JsDate/JsMap/JsSet/JsPromise/...) answers getProto() as null forever, but
        // its intrinsic prototype (RegExp.prototype, ...) still owns inherited enumerable properties
        // that for-in must walk. Synthesised once per walk so the fallback proto's own real chain
        // (up through Object.prototype) takes over from there instead of re-synthesising forever.
        var synthesised = false;
        for (var current = target; current != null;) {
            for (final var key : ownStringKeys(current)) {
                if (seen.add(key) && isEnumerableOwn(current, key)) {
                    result.add(key);
                }
            }
            // A proxy answers for its whole chain through its own ownKeys trap.
            if (current instanceof JsProxy || current instanceof JsGlobalObject) {
                break;
            }
            final var next = current.getProto();
            if (next == null && !synthesised && !(current instanceof JsObject)) {
                current = interp.intrinsics().protoFor(current);
                synthesised = true;
            } else {
                current = next;
            }
        }
        return result;
    }

    private List<String> ownStringKeys(JsValue target) {
        if (target instanceof JsGlobalObject global) {
            return new ArrayList<>(global.getEnv().allGlobalNames());
        }
        if (target instanceof JsProxy proxy) {
            final var keys = new ArrayList<String>();
            for (final var key : proxies.ownKeys(proxy)) {
                if (key instanceof JsString string) {
                    keys.add(string.getValue());
                }
            }
            return keys;
        }
        if (target instanceof JsClass cls) {
            return new ArrayList<>(cls.getStaticOwner().keys());
        }
        if (target instanceof JsObject object) {
            return new ArrayList<>(object.keys());
        }
        if (target instanceof JsArray array) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < array.length(); i++) {
                if (!array.isHole(i)) {
                    keys.add(Integer.toString(i));
                }
            }
            keys.addAll(array.namedPropertyKeys());
            return keys;
        }
        if (target instanceof JsString string) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < string.getValue().length(); i++) {
                keys.add(Integer.toString(i));
            }
            return keys;
        }
        if (target instanceof JsTypedArray typed) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < typed.length(); i++) {
                keys.add(Integer.toString(i));
            }
            final var table = typed.ownProperties();
            if (table != null) {
                keys.addAll(table.keys());
            }
            return keys;
        }
        if (target instanceof JsArguments arguments) {
            return arguments.enumerablePropertyKeys();
        }
        if (target instanceof JsCallableProperties callable) {
            return callable.enumerablePropertyKeys();
        }
        return List.of();
    }

    private boolean isEnumerableOwn(JsValue target, String key) {
        return switch (target) {
            case JsGlobalObject global -> global.getEnv().enumerableGlobalNames().contains(key);
            case JsProxy proxy -> isEnumerableProxyKey(proxy, new JsString(key));
            case JsClass cls -> cls.getStaticOwner().isEnumerable(key);
            case JsObject object -> object.isEnumerable(key);
            case JsArray array -> arrayKeyEnumerable(array, key);
            case JsTypedArray typed -> typedArrayKeyEnumerable(typed, key);
            default -> true;
        };
    }

    private static boolean arrayKeyEnumerable(JsArray array, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index == null ? array.getPropFlags(key).enumerable() : array.getIndexFlags(index).enumerable();
    }

    // Canonical index keys are always enumerable (the fixed INDEX_FLAGS getOwnProperty answers with);
    // any other own key was set through the ordinary PropertyTable, whose per-key flags govern it.
    private static boolean typedArrayKeyEnumerable(JsTypedArray typed, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null && index < typed.length()) {
            return true;
        }
        final var table = typed.ownProperties();
        return table != null && table.isEnumerable(key);
    }

    public Completion evalLabeled(LabeledStatement statement, Environment env) {
        final var label = statement.getLabel().getName();
        final var body = statement.getBody();
        final var completion = switch (body.getType()) {
            case WHILE_STATEMENT -> evalWhile((WhileStatement) body, env, label);
            case DO_WHILE_STATEMENT -> evalDoWhile((DoWhileStatement) body, env, label);
            case FOR_STATEMENT -> evalFor((ForStatement) body, env, label);
            case FOR_IN_STATEMENT -> evalForIn((ForInStatement) body, env, label);
            case FOR_OF_STATEMENT -> evalForOf((ForOfStatement) body, env, label);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) body, env, label);
            default -> interp.evalStatement(body, env);
        };
        if (completion.kind() == Completion.Kind.BREAK && label.equals(completion.label())) {
            return Completion.empty();
        }
        return completion;
    }

    private LoopAction classify(Completion completion, String label) {
        return switch (completion.kind()) {
            case NORMAL -> LoopAction.CONTINUE_LOOP;
            case CONTINUE -> matchesLabel(completion.label(), label) ? LoopAction.CONTINUE_LOOP : LoopAction.PROPAGATE;
            case BREAK -> matchesLabel(completion.label(), label) ? LoopAction.BREAK_LOOP : LoopAction.PROPAGATE;
            case RETURN -> LoopAction.PROPAGATE;
        };
    }

    public Completion evalReturn(ReturnStatement statement, Environment env) {
        final var argument = statement.getArgument();
        return Completion.returnValue(argument == null ? JsUndefined.getInstance() : interp.eval(argument, env));
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

    // The switch Expression is evaluated in the outer environment, before the block environment
    // (whose lexical declarations spans every case) is created - only CaseBlockEvaluation (the case
    // tests and consequents) runs inside it.
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
}
