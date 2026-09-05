package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.ObjectBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptCancelledException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.interpreter.BindingEvaluator;
import org.techhouse.simplejs.internal.interpreter.CallStack;
import org.techhouse.simplejs.internal.interpreter.ClassEvaluator;
import org.techhouse.simplejs.internal.interpreter.ExpressionEvaluator;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.internal.interpreter.MemberEvaluator;
import org.techhouse.simplejs.internal.interpreter.ModuleEvaluator;
import org.techhouse.simplejs.internal.interpreter.ModuleRegistry;
import org.techhouse.simplejs.internal.interpreter.ProxyDispatch;
import org.techhouse.simplejs.internal.interpreter.StackCapture;
import org.techhouse.simplejs.internal.interpreter.StatementEvaluator;
import org.techhouse.simplejs.internal.interpreter.VarHoisting;
import org.techhouse.simplejs.internal.interpreter.YieldDelegation;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportExpression;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MetaProperty;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Interpreter {
    // Sentinel propagated up an optional chain once a link with `?.` sees a nullish base, so every
    // later access in the same chain is skipped; unwrapped to `undefined` at the top of the chain.
    private static final JsValue SHORT_CIRCUIT = new JsValue() {
    };

    public record ProgramOutcome(JsValue lastValue, boolean hasReturn, JsValue returnValue, JsValue exportDefault,
            Map<String, JsValue> namedExports) {
    }

    private final EventLoop eventLoop = new EventLoop();
    private final ThreadLocal<Coroutine> currentCoroutine = new ThreadLocal<>();
    private final List<Coroutine> coroutines = new ArrayList<>();
    private final InterpreterOps ops = new InterpreterOps() {
        @Override
        public JsValue getMember(JsValue target, JsValue key) {
            return getMemberByKey(target, key);
        }

        @Override
        public JsValue getMemberWithReceiver(JsValue target, JsValue key, JsValue receiver) {
            return getMemberByKey(target, key, receiver);
        }

        @Override
        public boolean setMember(JsValue target, JsValue key, JsValue value) {
            return setMemberByKey(target, key, value);
        }

        @Override
        public boolean setMemberWithReceiver(JsValue target, JsValue key, JsValue value, JsValue receiver) {
            return setMemberByKey(target, key, value, receiver);
        }

        @Override
        public boolean has(JsValue target, JsValue key) {
            return hasMember(target, key);
        }

        @Override
        public boolean deleteMember(JsValue target, JsValue key) {
            return deleteMemberValue(target, key);
        }

        @Override
        public List<JsValue> ownKeys(JsValue target) {
            return ownKeysOf(target);
        }

        @Override
        public JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args) {
            return callValue(fn, thisArg, args);
        }

        @Override
        public JsValue construct(JsValue fn, List<JsValue> args, JsValue newTarget) {
            return constructValue(fn, args, newTarget);
        }

        @Override
        public JsValue getPrototypeOf(JsValue target) {
            if (target instanceof JsProxy proxy) {
                return proxies.getPrototypeOf(proxy);
            }
            if (target instanceof JsNativeFunction nativeFunction && nativeFunction.getOwnProto() != null) {
                return nativeFunction.getOwnProto();
            }
            if (target instanceof JsObject || isNullish(target)) {
                return ObjectBuiltins.getPrototypeOf(List.of(target));
            }
            // An explicit [[Prototype]] (Object.setPrototypeOf(arr, other)) wins over the realm's
            // intrinsic default for the type - JsArray carries its own proto slot the same way
            // JsClass/JsGenerator/JsAsyncGenerator do, but was missing here, so Object.getPrototypeOf
            // on an array with an explicit prototype wrongly reported Array.prototype instead.
            if ((target instanceof JsClass || target instanceof JsGenerator || target instanceof JsAsyncGenerator
                    || target instanceof JsArray) && target.getProto() != null) {
                return target.getProto();
            }
            return intrinsics.protoFor(target);
        }

        @Override
        public boolean setPrototypeOf(JsValue target, JsValue proto) {
            if (target instanceof JsProxy proxy) {
                return proxies.setPrototypeOf(proxy, proto);
            }
            return ObjectBuiltins.trySetPrototypeOf(target, proto, intrinsics);
        }

        @Override
        public boolean isExtensible(JsValue target) {
            return target instanceof JsProxy proxy
                    ? proxies.isExtensible(proxy)
                    : JsCoercion.toBoolean(ObjectBuiltins.isExtensible(List.of(target)));
        }

        @Override
        public boolean preventExtensions(JsValue target) {
            if (target instanceof JsProxy proxy) {
                return proxies.preventExtensions(proxy);
            }
            ObjectBuiltins.preventExtensions(List.of(target));
            return true;
        }

        @Override
        public boolean defineProperty(JsValue target, JsValue key, JsValue descriptor) {
            final var propertyKey = JsCoercion.toPropertyKey(key, this);
            if (target instanceof JsProxy proxy) {
                return proxies.defineProperty(proxy, propertyKey, descriptor);
            }
            ObjectBuiltins.defineProperty(List.of(target, propertyKey, descriptor), this);
            return true;
        }

        @Override
        public JsValue getOwnPropertyDescriptor(JsValue target, JsValue key) {
            final var propertyKey = JsCoercion.toPropertyKey(key, this);
            return target instanceof JsProxy proxy
                    ? proxies.getOwnPropertyDescriptor(proxy, propertyKey)
                    : ObjectBuiltins.getOwnPropertyDescriptor(List.of(target, propertyKey));
        }

        @Override
        public java.time.ZoneId timeZone() {
            return host.timeZone();
        }

        @Override
        public java.util.Locale locale() {
            return host.locale();
        }

        @Override
        public void charge(long bytes) {
            Interpreter.this.charge(bytes);
        }

        @Override
        public void release(long bytes) {
            Interpreter.this.release(bytes);
        }

        @Override
        public void tick() {
            Interpreter.this.tick();
        }
    };
    private final ProxyDispatch proxies = new ProxyDispatch(ops);
    private final Intrinsics intrinsics = new Intrinsics(this::callValue, ops, eventLoop, this::driveAsyncGenerator);
    private final MemberEvaluator members = new MemberEvaluator(this, eventLoop, proxies);
    private final BindingEvaluator binding = new BindingEvaluator(this, members);
    private final ClassEvaluator classes = new ClassEvaluator(this);
    private final StatementEvaluator statements = new StatementEvaluator(this, members, proxies);
    private final ExpressionEvaluator expressions = new ExpressionEvaluator(this, classes, proxies);
    private final ModuleEvaluator modules;

    private final HostBindings host;
    private final ModuleRegistry moduleRegistry = new ModuleRegistry();
    private final int maxDepth;
    private final int maxModuleDepth;
    private long instructionsRemaining;
    private long bytesRemaining;
    private final long memoryBudget;
    private final long deadlineNanos;
    private final CancellationToken cancellation;
    private int depth;
    private int moduleDepth;
    private final CallStack callStack = new CallStack();
    private Environment globalEnv;
    private ModuleBodyWrapper moduleBodyWrapper;
    // Set once by runModule (one Interpreter per script run/realm). GetBindingValue on a Global
    // Environment Record consults HasProperty/Get on the global object itself, not just its
    // declared var/function bindings - a property added directly on globalThis via
    // Object.defineProperty/defineProperties (in particular an accessor, which JsGlobalObject keeps
    // in its own PropertyTable rather than as an Environment binding - see
    // JsGlobalObject.defineOwnProperty) is reachable as a bare identifier even though
    // Environment.isDeclared answers false for it.
    private JsGlobalObject globalObjectValue;

    private Interpreter(HostBindings host) {
        this.host = host;
        this.modules = new ModuleEvaluator(this, classes, host, eventLoop);
        eventLoop.wireInterpreter(ops, intrinsics);
        final var limits = host.limits();
        this.maxDepth = limits.maxDepth();
        this.maxModuleDepth = limits.maxModuleDepth();
        this.instructionsRemaining = limits.instructionBudget();
        this.bytesRemaining = limits.memoryBudget();
        this.memoryBudget = limits.memoryBudget();
        this.deadlineNanos = limits.wallClockMillis() > 0
                ? System.nanoTime() + limits.wallClockMillis() * 1_000_000L
                : -1;
        this.cancellation = host.cancellation();
        eventLoop.wireCancellation(cancellation);
    }

    public static JsValue run(Program program) {
        return new Interpreter(SimpleHostBindings.empty()).evalProgram(program);
    }

    public static JsValue run(String source) {
        return run(Parser.parse(Lexer.lexWithPositions(source)));
    }

    public static ProgramOutcome run(Program program, HostBindings host) {
        return new Interpreter(host).runModule(program, (outcome, ignored) -> outcome);
    }

    /**
     * Runs the program and maps its outcome to a result while the interpreter is still alive, so the mapping
     * may invoke user code - an accessor-valued property on the returned object, in particular. The mapping is
     * charged against the run's own budgets, and the loop is drained again afterwards in case it queued work.
     */
    public static <T> T run(Program program, HostBindings host, ModuleBodyWrapper around, ResultFinisher<T> finisher) {
        final var interpreter = new Interpreter(host);
        interpreter.moduleBodyWrapper = around;
        return interpreter.runModule(program, finisher);
    }

    @FunctionalInterface
    public interface ResultFinisher<T> {
        T finish(ProgramOutcome outcome, InterpreterOps ops);
    }

    /**
     * Runs the program with {@code around} wrapping the module body. The wrapper is invoked on the thread the
     * body itself runs on, which is what lets a caller open a transaction around the whole script: the
     * collection locks a transactional write takes are thread-owned, so beginning or committing from the
     * calling thread instead would strand them.
     */
    public static ProgramOutcome run(Program program, HostBindings host, ModuleBodyWrapper around) {
        return run(program, host, around, (outcome, ignored) -> outcome);
    }

    @FunctionalInterface
    public interface ModuleBodyWrapper {
        void around(Runnable body);
    }

    public static ProgramOutcome run(String source, HostBindings host) {
        return run(Parser.parse(Lexer.lexWithPositions(source)), host);
    }

    public void tick() {
        if (instructionsRemaining >= 0) {
            if (instructionsRemaining == 0) {
                throw new ScriptLimitException("Script exceeded its instruction budget");
            }
            instructionsRemaining--;
        }
        if (deadlineNanos >= 0 && System.nanoTime() >= deadlineNanos) {
            throw new ScriptTimeoutException("Script exceeded its time limit");
        }
        if (cancellation != null && cancellation.isCancelled()) {
            throw new ScriptCancelledException("Script was cancelled");
        }
    }

    public void charge(long bytes) {
        if (bytesRemaining < 0 || bytes <= 0) {
            return;
        }
        if (bytes > bytesRemaining) {
            throw new ScriptMemoryException("Script exceeded its memory budget");
        }
        bytesRemaining -= bytes;
    }

    // The counterpart of charge(), for a host allocation the engine knows is discarded at a fixed
    // point - a db.cursor batch replaced by the next one. Deterministic, so it does not reintroduce the
    // GC-timing dependence the budget deliberately avoids.
    public void release(long bytes) {
        if (bytesRemaining < 0 || bytes <= 0) {
            return;
        }
        bytesRemaining = Math.min(memoryBudget, bytesRemaining + bytes);
    }

    private JsValue evalProgram(Program program) {
        return runModule(program, (outcome, ignored) -> outcome).lastValue();
    }

    private <T> T runModule(Program program, ResultFinisher<T> finisher) {
        final var previousStack = StackCapture.install(callStack);
        try {
            final var outcome = evaluateTopLevelModule(program);
            final var finished = finisher.finish(outcome, ops);
            eventLoop.drain(deadlineNanos);
            reportUnhandledRejections();
            return finished;
        } finally {
            cancelPendingCoroutines();
            StackCapture.restore(previousStack);
        }
    }

    private ProgramOutcome evaluateTopLevelModule(Program program) {
        final var env = Environment.global();
        final var globalThis = GlobalScope.install(env, eventLoop, this::callValue, this::iterableToList,
                host.console(), ops, host.network(), host.limits(), intrinsics);
        env.defineThis(globalThis);
        globalObjectValue = globalThis;
        globalEnv = env;
        final var result = new ModuleResult();
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markAsync();
        coroutine.startAsync(() -> {
            currentCoroutine.set(coroutine);
            if (moduleBodyWrapper == null) {
                evaluateModuleBody(program, env, result);
            } else {
                moduleBodyWrapper.around(() -> evaluateModuleBody(program, env, result));
            }
            return JsUndefined.getInstance();
        });
        markContractPromiseHandled(result);
        eventLoop.drain(deadlineNanos);
        return new ProgramOutcome(result.last, result.hasReturn, result.returnValue, result.exportDefault,
                result.namedExports);
    }

    private void cancelPendingCoroutines() {
        for (final var pending : coroutines) {
            if (!pending.isDone()) {
                pending.cancel();
            }
        }
    }

    /**
     * Evaluates the module body and keeps the interpreter alive so the caller can invoke a value it exported
     * many times - one realm, and therefore one instruction budget, deadline and memory budget for the whole
     * sequence of calls rather than a fresh one per call.
     */
    public static Session open(Program program, HostBindings host) {
        final var interpreter = new Interpreter(host);
        final var previousStack = StackCapture.install(interpreter.callStack);
        try {
            return new Session(interpreter, interpreter.evaluateTopLevelModule(program), previousStack);
        } catch (RuntimeException | Error failure) {
            interpreter.reportUnhandledRejections();
            interpreter.cancelPendingCoroutines();
            StackCapture.restore(previousStack);
            throw failure;
        }
    }

    public static final class Session implements AutoCloseable {
        private final Interpreter interpreter;
        private final ProgramOutcome outcome;
        private final CallStack previousStack;
        private boolean closed;

        private Session(Interpreter interpreter, ProgramOutcome outcome, CallStack previousStack) {
            this.interpreter = interpreter;
            this.outcome = outcome;
            this.previousStack = previousStack;
        }

        public ProgramOutcome outcome() {
            return outcome;
        }

        public JsValue call(JsValue fn, List<JsValue> args) {
            if (closed) {
                throw new SimpleJsRuntimeException("Script session is closed");
            }
            final var value = interpreter.callValue(fn, JsUndefined.getInstance(), args);
            interpreter.eventLoop.drain(interpreter.deadlineNanos);
            return value;
        }

        public void charge(long bytes) {
            interpreter.charge(bytes);
        }

        public void release(long bytes) {
            interpreter.release(bytes);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                interpreter.eventLoop.drain(interpreter.deadlineNanos);
                interpreter.reportUnhandledRejections();
            } catch (RuntimeException | Error ignored) {
                // A script whose pending work aborts on the way out has nothing left to report: the
                // caller already has its per-document outcome, and the coroutines below still have to be
                // cancelled or their virtual threads would park forever.
            } finally {
                interpreter.cancelPendingCoroutines();
                StackCapture.restore(previousStack);
            }
        }
    }

    // The host contract awaits a promise returned (or default-exported) at top level, so it is
    // already handled by the time the drain looks for unhandled rejections.
    private static void markContractPromiseHandled(ModuleResult result) {
        final var contract = result.hasReturn ? result.returnValue : result.exportDefault;
        if (contract instanceof JsPromise promise) {
            promise.markHandled();
        }
    }

    private void reportUnhandledRejections() {
        if (!host.limits().reportUnhandledRejections()) {
            return;
        }
        final var sink = host.console();
        if (sink == null) {
            return;
        }
        for (final var promise : eventLoop.promises()) {
            if (promise.isUnhandledRejection()) {
                sink.accept("UnhandledPromiseRejection: " + JsCoercion.toStr(promise.getResult()));
            }
        }
    }

    private void evaluateModuleBody(Program program, Environment env, ModuleResult result) {
        for (final var statement : program.getBody()) {
            if (statement instanceof ImportDeclaration importDeclaration) {
                modules.bindImport(importDeclaration, env);
            }
        }
        VarHoisting.hoistVars(program.getBody(), env);
        hoist(program.getBody(), env);
        RuntimeException pending = null;
        try {
            runModuleStatements(program, env, result);
        } catch (ScriptAbortException abort) {
            throw abort;
        } catch (RuntimeException error) {
            pending = error;
        }
        statements.disposeScope(env, Completion.empty(), pending);
    }

    // An imported module shares this interpreter's realm, event loop, coroutine and instruction
    // counter: a second Interpreter would give it prototypes the importer cannot match, an event loop
    // nothing drains, a thread that breaks an open transaction's affinity, and a fresh budget.
    public JsValue importModule(String moduleId, String displayName, Supplier<Program> parser) {
        final var state = moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return moduleRegistry.namespaceOf(moduleId);
        }
        if (state == ModuleRegistry.State.FAILED) {
            throw moduleRegistry.failureOf(moduleId);
        }
        if (state == ModuleRegistry.State.EVALUATING) {
            throw new JsThrowException(intrinsics.makeError("Error", "Circular import of module '" + moduleId + "'"));
        }
        if (maxModuleDepth >= 0 && moduleDepth >= maxModuleDepth) {
            throw new ScriptLimitException("Script exceeded its maximum module nesting depth");
        }
        moduleRegistry.beginEvaluation(moduleId);
        moduleDepth++;
        final var previousModule = callStack.enterModule(displayName);
        try {
            final var namespace = evaluateModule(parser.get());
            moduleRegistry.complete(moduleId, namespace);
            return namespace;
        } catch (RuntimeException error) {
            moduleRegistry.fail(moduleId, error);
            throw error;
        } finally {
            callStack.exitModule(previousModule);
            moduleDepth--;
        }
    }

    public JsValue cacheBuiltinModule(String moduleId, Supplier<JsValue> factory) {
        final var state = moduleRegistry.stateOf(moduleId);
        if (state == ModuleRegistry.State.EVALUATED) {
            return moduleRegistry.namespaceOf(moduleId);
        }
        final var module = factory.get();
        moduleRegistry.complete(moduleId, module);
        return module;
    }

    private JsValue evaluateModule(Program program) {
        final var env = globalEnv.functionChild();
        final var result = new ModuleResult();
        evaluateModuleBody(program, env, result);
        final var namespace = new JsObject();
        result.namedExports.forEach(namespace::set);
        namespace.set("default", result.exportDefault == null ? JsUndefined.getInstance() : result.exportDefault);
        return namespace;
    }

    private void runModuleStatements(Program program, Environment env, ModuleResult result) {
        moduleLoop : for (final var statement : program.getBody()) {
            switch (statement) {
                case ImportDeclaration ignored -> {
                    // already bound in the pre-pass above
                }
                case ExportDefaultDeclaration exportDefaultDeclaration ->
                    result.exportDefault = modules.evalExportDefault(exportDefaultDeclaration, env);
                case ExportNamedDeclaration exportNamedDeclaration ->
                    modules.evalExportNamed(exportNamedDeclaration, env, result.namedExports);
                case ExportAllDeclaration exportAllDeclaration ->
                    modules.evalExportAll(exportAllDeclaration, result.namedExports);
                default -> {
                    final var completion = evalStatement(statement, env);
                    if (completion.kind() == Completion.Kind.RETURN) {
                        result.hasReturn = true;
                        result.returnValue = completion.value();
                        break moduleLoop;
                    }
                    if (!completion.isNormal()) {
                        throw new SyntaxErrorException(
                                "Illegal " + completion.kind().name().toLowerCase(Locale.ROOT) + " statement");
                    }
                    result.last = completion.value();
                }
            }
        }
    }

    private static final class ModuleResult {
        private JsValue last = JsUndefined.getInstance();
        private final Map<String, JsValue> namedExports = new LinkedHashMap<>();
        private JsValue exportDefault;
        private boolean hasReturn;
        private JsValue returnValue = JsUndefined.getInstance();
    }

    public void hoist(List<Statement> body, Environment env) {
        for (final var raw : body) {
            final var statement = raw instanceof ExportNamedDeclaration export
                    && export.getDeclaration() instanceof Statement inner ? inner : raw;
            if (statement instanceof VariableDeclaration declaration) {
                final var kind = declaration.getKind();
                for (final var declarator : declaration.getDeclarations()) {
                    final var names = new ArrayList<String>();
                    collectBoundNames(declarator.getId(), names);
                    for (final var name : names) {
                        if (LEXICAL_KINDS.contains(kind)) {
                            env.declareLexical(name, kind);
                        } else if (USING_KINDS.contains(kind)) {
                            env.declareLexical(name, "const");
                        } else if ("var".equals(kind)) {
                            env.declareVar(name);
                        }
                    }
                }
            } else if (statement instanceof FunctionDeclaration declaration) {
                final var name = declaration.getName().getName();
                final var function = makeFunction(name, declaration.getParams(), declaration.getBody(), false, false,
                        declaration.isAsync(), declaration.isGenerator(), env, declaration.getSourceText());
                env.declareFunction(name, function);
            }
        }
    }

    public Completion evalStatement(Statement statement, Environment env) {
        callStack.setPosition(statement.getPosition());
        return switch (statement.getType()) {
            case BLOCK_STATEMENT -> statements.evalBlock((BlockStatement) statement, env);
            case EMPTY_STATEMENT -> Completion.empty();
            case EXPRESSION_STATEMENT ->
                Completion.normal(eval(((ExpressionStatement) statement).getExpression(), env));
            case VARIABLE_DECLARATION -> evalVariableDeclaration((VariableDeclaration) statement, env);
            case IF_STATEMENT -> statements.evalIf((IfStatement) statement, env);
            case WHILE_STATEMENT -> statements.evalWhile((WhileStatement) statement, env, null);
            case DO_WHILE_STATEMENT -> statements.evalDoWhile((DoWhileStatement) statement, env, null);
            case FOR_STATEMENT -> statements.evalFor((ForStatement) statement, env, null);
            case FOR_IN_STATEMENT -> statements.evalForIn((ForInStatement) statement, env, null);
            case FOR_OF_STATEMENT -> statements.evalForOf((ForOfStatement) statement, env, null);
            case LABELED_STATEMENT -> statements.evalLabeled((LabeledStatement) statement, env);
            case SWITCH_STATEMENT -> statements.evalSwitch((SwitchStatement) statement, env, null);
            case BREAK_STATEMENT -> Completion.breakOut(labelName(((BreakStatement) statement).getLabel()));
            case CONTINUE_STATEMENT -> Completion.continueOut(labelName(((ContinueStatement) statement).getLabel()));
            case RETURN_STATEMENT -> statements.evalReturn((ReturnStatement) statement, env);
            case THROW_STATEMENT -> throw new JsThrowException(eval(((ThrowStatement) statement).getArgument(), env));
            case TRY_STATEMENT -> statements.evalTry((TryStatement) statement, env);
            case CLASS_DECLARATION -> classes.evalClassDeclaration((ClassDeclaration) statement, env);
            case FUNCTION_DECLARATION -> Completion.empty();
            case IMPORT_DECLARATION -> {
                modules.bindImport((ImportDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_NAMED_DECLARATION -> {
                final var declaration = ((ExportNamedDeclaration) statement).getDeclaration();
                yield declaration instanceof Statement inner ? evalStatement(inner, env) : Completion.empty();
            }
            case EXPORT_DEFAULT_DECLARATION -> {
                modules.evalExportDefault((ExportDefaultDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_ALL_DECLARATION -> Completion.empty();
            default -> throw new UnsupportedNodeException(statement.getType().name());
        };
    }

    public Completion evalVariableDeclaration(VariableDeclaration declaration, Environment env) {
        return binding.evalVariableDeclaration(declaration, env);
    }

    public void bindForTarget(JsNode left, JsValue value, Environment env) {
        binding.bindForTarget(left, value, env);
    }

    public Completion evalCatch(CatchClause handler, JsValue error, Environment env) {
        return binding.evalCatch(handler, error, env);
    }

    public Completion evalBlock(BlockStatement block, Environment env) {
        return statements.evalBlock(block, env);
    }

    // NamedEvaluation, but for the one case where timing is observable: a class expression's
    // static field initializers/static blocks run as part of ClassDefinitionEvaluation itself, so
    // an anonymous class's inferred name has to be set *before* that (ClassEvaluator.buildClass
    // applies it right after member installation, before runStaticInit) rather than patched on
    // after the value comes back - a plain SetFunctionName post-evaluation (what applyInferredName
    // does) is too late for `var C = class { static f = this.name }`. A function expression's
    // inferred name has no such observer (it is only readable once the function is later called),
    // so every other NamedEvaluation site keeps going through the ordinary eval + applyInferredName
    // path below.
    public JsValue evalNamed(Expression expression, Environment env, String name) {
        if (name != null && expression instanceof ClassExpression classExpr && classExpr.getId() == null) {
            return classes.evalClassExpression(classExpr, env, name);
        }
        final var value = eval(expression, env);
        applyInferredName(expression, value, name);
        return value;
    }

    public JsValue eval(Expression expression, Environment env) {
        return switch (expression.getType()) {
            case NUMBER_LITERAL -> new JsNumber(((NumberLiteral) expression).getValue().doubleValue());
            case BIGINT_LITERAL -> new JsBigInt(((BigIntLiteral) expression).getValue());
            case STRING_LITERAL -> new JsString(((StringLiteral) expression).getValue());
            case BOOLEAN_LITERAL -> JsBoolean.of(((BooleanLiteral) expression).getValue());
            case NULL_LITERAL -> JsNull.getInstance();
            case UNDEFINED_LITERAL -> JsUndefined.getInstance();
            case REGEX_LITERAL -> RegexTranslator.compile(((RegexLiteral) expression).getPattern(),
                    ((RegexLiteral) expression).getFlags());
            case TEMPLATE_LITERAL -> expressions.evalTemplate((TemplateLiteral) expression, env);
            case TAGGED_TEMPLATE_EXPRESSION ->
                expressions.evalTaggedTemplate((TaggedTemplateExpression) expression, env);
            case IDENTIFIER -> env.get(((Identifier) expression).getName());
            case THIS_EXPRESSION -> env.resolveThis();
            case FUNCTION_EXPRESSION -> evalFunctionExpression((FunctionExpression) expression, env);
            case ARROW_FUNCTION_EXPRESSION -> evalArrowFunction((ArrowFunctionExpression) expression, env);
            case CALL_EXPRESSION -> unwrapShortCircuit(evalCall((CallExpression) expression, env));
            case NEW_EXPRESSION -> evalNew((NewExpression) expression, env);
            case ARRAY_EXPRESSION -> expressions.evalArray((ArrayExpression) expression, env);
            case OBJECT_EXPRESSION -> expressions.evalObject((ObjectExpression) expression, env);
            case UNARY_EXPRESSION -> expressions.evalUnary((UnaryExpression) expression, env);
            case UPDATE_EXPRESSION -> expressions.evalUpdate((UpdateExpression) expression, env);
            case BINARY_EXPRESSION -> expressions.evalBinary((BinaryExpression) expression, env);
            case LOGICAL_EXPRESSION -> expressions.evalLogical((LogicalExpression) expression, env);
            case ASSIGNMENT_EXPRESSION -> expressions.evalAssignment((AssignmentExpression) expression, env);
            case CONDITIONAL_EXPRESSION -> expressions.evalConditional((ConditionalExpression) expression, env);
            case SEQUENCE_EXPRESSION -> expressions.evalSequence((SequenceExpression) expression, env);
            case MEMBER_EXPRESSION -> unwrapShortCircuit(evalMember((MemberExpression) expression, env));
            case CLASS_EXPRESSION -> classes.evalClassExpression((ClassExpression) expression, env);
            case YIELD_EXPRESSION -> evalYield((YieldExpression) expression, env);
            case AWAIT_EXPRESSION -> evalAwait((AwaitExpression) expression, env);
            case IMPORT_EXPRESSION -> modules.evalImportExpression((ImportExpression) expression, env);
            case META_PROPERTY -> evalMetaProperty((MetaProperty) expression, env);
            case SUPER_EXPRESSION -> throw new SyntaxErrorException("'super' keyword unexpected here");
            default -> throw new UnsupportedNodeException(expression.getType().name());
        };
    }

    private JsValue evalMetaProperty(MetaProperty meta, Environment env) {
        return "new".equals(meta.getMeta()) ? env.resolveNewTarget() : modules.evalMetaProperty();
    }

    public boolean hasMember(JsValue container, JsValue keyValue) {
        return switch (container) {
            case JsProxy proxy -> proxies.has(proxy, JsCoercion.toPropertyKey(keyValue, ops));
            case JsGlobalObject global -> global.getEnv().isDeclared(JsCoercion.toStr(keyValue));
            case JsObject object when keyValue instanceof JsSymbol symbol -> hasSymbolMember(object, symbol);
            case JsObject object -> hasStringMember(object, JsCoercion.toStr(keyValue));
            case JsClass cls when keyValue instanceof JsSymbol symbol -> hasStaticSymbolMember(cls, symbol);
            case JsClass cls -> hasStaticMember(cls, JsCoercion.toStr(keyValue));
            case JsArray array when keyValue instanceof JsSymbol -> exoticHasMember(array, keyValue);
            case JsArray array -> arrayHasMember(array, JsCoercion.toStr(keyValue)) || exoticHasMember(array, keyValue);
            // Unlike JsArray/JsTypedArray, every argument index is written directly into the own
            // property table at construction, so exoticHasMember alone already reflects a deletion
            // correctly; an extra range check would resurrect a deleted index as "present".
            case JsArguments arguments -> exoticHasMember(arguments, keyValue);
            // A canonical numeric index is answered only by the view itself: an out-of-range or
            // non-integral one is absent, never inherited from the prototype chain.
            case JsTypedArray typed when typed.hasCanonicalNumericIndex(keyValue) -> typed.hasOwnKey(keyValue);
            case JsTypedArray typed -> indexInRange(keyValue, typed.length()) || exoticHasMember(typed, keyValue);
            case JsCallableProperties callable when keyValue instanceof JsSymbol ->
                exoticHasMember((JsValue) callable, keyValue);
            case JsCallableProperties callable -> callableHasMember(callable, JsCoercion.toStr(keyValue));
            default -> {
                if (!isObjectLike(container)) {
                    throw new TypeErrorException(
                            "Cannot use 'in' operator to search for '" + JsCoercion.toStr(keyValue) + "'");
                }
                yield exoticHasMember(container, keyValue);
            }
        };
    }

    // An index-addressed exotic stores its elements outside the property table, so presence there is
    // a range check: an element holding `undefined` is present, unlike an array hole.
    private static boolean indexInRange(JsValue keyValue, int length) {
        if (keyValue instanceof JsSymbol) {
            return false;
        }
        final var key = JsCoercion.toStr(keyValue);
        final var index = arrayIndex(key);
        return index != null && index < length;
    }

    // Every exotic value type carries its own property table, so HasProperty on one is its own keys
    // plus its prototype chain rather than an outright rejection. An explicit [[Prototype]] (e.g.
    // Object.setPrototypeOf(array, proxy)) wins over the realm's intrinsic default for the type -
    // only a JsArray/JsClass can carry one today, everything else falls back to the intrinsic.
    private boolean exoticHasMember(JsValue container, JsValue keyValue) {
        final var table = container.ownProperties();
        if (table != null) {
            if (keyValue instanceof JsSymbol symbol && (table.hasSymbol(symbol) || table.hasSymbolAccessor(symbol))) {
                return true;
            }
            if (!(keyValue instanceof JsSymbol)) {
                final var key = JsCoercion.toStr(keyValue);
                if (table.has(key) || table.hasAccessor(key)) {
                    return true;
                }
            }
        }
        final var chainStart = container.getProto() == null ? intrinsics.protoFor(container) : container.getProto();
        if (keyValue instanceof JsSymbol symbol) {
            return members.chainHasSymbol(chainStart, symbol);
        }
        return members.chainHasKey(chainStart, JsCoercion.toStr(keyValue));
    }

    private boolean callableHasMember(JsCallableProperties callable, String key) {
        if (callable.hasProperty(key) || FunctionProtoBuiltins.metadata((JsValue) callable, key) != null) {
            return true;
        }
        // MakeConstructor installs an own "prototype" for a normal or generator function, but an
        // arrow, a concise method/getter/setter (kind "method"), and a plain async function never
        // get one.
        if (callable instanceof JsFunction function && "prototype".equals(key) && !function.isArrow()
                && !function.isMethod() && !(function.isAsync() && !function.isGenerator())) {
            return true;
        }
        return members.chainHasKey(intrinsics.protoFor((JsValue) callable), key);
    }

    private boolean hasStaticMember(JsClass cls, String key) {
        if ("prototype".equals(key) || "name".equals(key)) {
            return true;
        }
        if (cls.findStaticGetter(key) != null || cls.findStaticSetter(key) != null
                || cls.findStaticMethod(key) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStaticSymbolMember(JsClass cls, JsSymbol symbol) {
        if (cls.findStaticSymbolGetter(symbol) != null || cls.findStaticSymbolSetter(symbol) != null
                || cls.findStaticSymbolMethod(symbol) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticSymbolProp(symbol)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStringMember(JsObject object, String key) {
        return members.chainHasKey(object, key);
    }

    private boolean hasSymbolMember(JsObject object, JsSymbol symbol) {
        return members.chainHasSymbol(object, symbol);
    }

    private JsValue evalMember(MemberExpression member, Environment env) {
        if (member.getObject() instanceof SuperExpression) {
            return classes.evalSuperMemberRead(member, env);
        }
        final var target = evalChainObject(member.getObject(), env);
        if (target == SHORT_CIRCUIT) {
            return SHORT_CIRCUIT;
        }
        if (member.isOptional() && isNullish(target)) {
            return SHORT_CIRCUIT;
        }
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            return getPrivateMember(target, priv.getName(), env);
        }
        return getMemberByKey(target, memberKeyValue(member, env));
    }

    // Evaluate the object/callee side of a member or call while staying inside an optional chain:
    // member/call sub-expressions propagate the SHORT_CIRCUIT sentinel so a nullish link earlier in
    // the chain skips every later access; any other expression is a normal (chain-ending) evaluation.
    private JsValue evalChainObject(Expression expression, Environment env) {
        return switch (expression) {
            case MemberExpression member -> evalMember(member, env);
            case CallExpression call -> evalCall(call, env);
            default -> eval(expression, env);
        };
    }

    private JsValue unwrapShortCircuit(JsValue value) {
        return value == SHORT_CIRCUIT ? JsUndefined.getInstance() : value;
    }

    public String memberKey(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return JsCoercion.toStr(eval(member.getProperty(), env), ops);
        }
        if (member.getProperty() instanceof Identifier id) {
            return id.getName();
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    // The reference keeps the raw property value: ToPropertyKey is part of GetValue/PutValue, which
    // is why a poisoned toString is observed after a compound assignment's ToObject but only after
    // the right-hand side of a plain one.
    public JsValue memberKeyValue(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return eval(member.getProperty(), env);
        }
        if (member.getProperty() instanceof Identifier id) {
            return new JsString(id.getName());
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    // GetValue on a property reference: ToObject(base) first, so a nullish base is a TypeError before
    // the key is ever coerced, then ToPropertyKey - which may run a user toString.
    public JsValue getMemberByKey(JsValue target, JsValue keyValue) {
        if (target instanceof JsProxy proxy) {
            return proxies.get(proxy, JsCoercion.toPropertyKey(keyValue, ops));
        }
        requireObjectCoercible(target);
        final var key = JsCoercion.toPropertyKey(keyValue, ops);
        if (key instanceof JsSymbol symbol) {
            return members.getSymbolMember(target, symbol);
        }
        return members.getMember(target, ((JsString) key).getValue());
    }

    // A reference that is both read and written - a compound assignment or an update - coerces its key
    // exactly once, so the caller resolves it up front and passes the result to both halves.
    public JsValue referenceKey(JsValue target, JsValue rawKey) {
        if (!(target instanceof JsProxy)) {
            requireObjectCoercible(target);
        }
        return JsCoercion.toPropertyKey(rawKey, ops);
    }

    private static void requireObjectCoercible(JsValue target) {
        if (isNullish(target)) {
            throw new TypeErrorException(
                    "Cannot read properties of " + JsCoercion.toStr(target) + " (reading a computed property)");
        }
    }

    public JsValue getMemberByKey(JsValue target, JsValue keyValue, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            return proxies.get(proxy, JsCoercion.toPropertyKey(keyValue, ops), receiver);
        }
        if (keyValue instanceof JsSymbol symbol) {
            return members.getSymbolMember(target, symbol);
        }
        return members.getMember(target, JsCoercion.toStr(keyValue), receiver);
    }

    public JsValue getMember(JsValue target, String key) {
        return members.getMember(target, key);
    }

    public InterpreterOps ops() {
        return ops;
    }

    public Intrinsics intrinsics() {
        return intrinsics;
    }

    public EventLoop eventLoop() {
        return eventLoop;
    }

    public MemberEvaluator members() {
        return members;
    }

    public Coroutine currentCoroutine() {
        return currentCoroutine.get();
    }

    public boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value) {
        if (target instanceof JsProxy proxy) {
            return proxies.set(proxy, JsCoercion.toPropertyKey(rawKey, ops), value);
        }
        requireObjectCoercible(target);
        final var keyValue = JsCoercion.toPropertyKey(rawKey, ops);
        if (keyValue instanceof JsSymbol symbol) {
            if (target instanceof JsObject object) {
                if (object.hasSymbolAccessor(symbol)) {
                    final var setter = object.getSymbolAccessorSetter(symbol);
                    if (setter != null) {
                        callValue(setter, object, List.of(value));
                    }
                    return true;
                }
                final var cls = object.getKlass();
                if (cls != null && !object.hasSymbol(symbol)) {
                    final var setter = cls.findInstanceSymbolSetter(symbol);
                    if (setter != null) {
                        callFunction(setter, object, List.of(value));
                        return true;
                    }
                }
                return object.setSymbol(symbol, value);
            }
            if (target instanceof JsClass cls) {
                cls.setStaticSymbolProp(symbol, value);
                return true;
            }
            // Every other exotic object keeps its symbol keys in the same ordinary table, so the
            // write has to land there rather than being discarded.
            final var table = target.ownProperties();
            return table == null || table.setSymbol(symbol, value);
        }
        if (!isObjectLike(target)) {
            return setPrimitiveMember(target, ((JsString) keyValue).getValue(), value, target);
        }
        return members.setMember(target, ((JsString) keyValue).getValue(), value);
    }

    public boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            return proxies.set(proxy, JsCoercion.toPropertyKey(rawKey, ops), value, receiver);
        }
        requireObjectCoercible(target);
        final var keyValue = JsCoercion.toPropertyKey(rawKey, ops);
        if (keyValue instanceof JsSymbol) {
            return setMemberByKey(target, keyValue, value);
        }
        if (!isObjectLike(target)) {
            return setPrimitiveMember(target, ((JsString) keyValue).getValue(), value, receiver);
        }
        return members.setMember(target, ((JsString) keyValue).getValue(), value, receiver);
    }

    // PutValue on a primitive base (OrdinarySetWithOwnDescriptor's "If Receiver is not an Object,
    // return false" step): a data-property write can never succeed on a primitive receiver, no
    // matter how far up the prototype chain the key resolves or whether it is writable there -
    // there is nowhere to create or overwrite an own property. Only a setter found somewhere along
    // the chain is invoked (with `this` bound to the receiver, per GetThisValue), and a Proxy
    // encountered along the way runs its own "set" trap instead of being walked past
    // (put-value-prop-base-primitive.js relies on exactly this - MemberEvaluator.setMember's
    // `default` arm wrongly treats every primitive target's null ownProperties() as "nothing to do,
    // report success").
    private boolean setPrimitiveMember(JsValue target, String key, JsValue value, JsValue receiver) {
        final var keyValue = new JsString(key);
        for (JsValue link = intrinsics.protoFor(target); link != null;) {
            if (link instanceof JsProxy proxy) {
                return proxies.set(proxy, keyValue, value, receiver);
            }
            if (!(link instanceof JsObject object)) {
                return false;
            }
            if (object.hasAccessor(key)) {
                final var setter = object.getAccessorSetter(key);
                if (setter == null) {
                    return false;
                }
                callValue(setter, receiver, List.of(value));
                return true;
            }
            link = object.getProto();
        }
        return false;
    }

    // A named function expression gets its own scope holding an immutable binding for that name, so
    // the body can call itself and an assignment to it is a TypeError rather than a stray global.
    private JsValue evalFunctionExpression(FunctionExpression expression, Environment env) {
        if (expression.getName() == null) {
            return makeFunction(null, expression.getParams(), expression.getBody(), false, false, expression.isAsync(),
                    expression.isGenerator(), env, expression.getSourceText());
        }
        final var name = expression.getName().getName();
        final var funcEnv = env.child();
        final var function = makeFunction(name, expression.getParams(), expression.getBody(), false, false,
                expression.isAsync(), expression.isGenerator(), funcEnv, expression.getSourceText());
        funcEnv.declareLexical(name, "const");
        funcEnv.initialize(name, function);
        return function;
    }

    private JsValue evalArrowFunction(ArrowFunctionExpression expression, Environment env) {
        return makeFunction(null, expression.getParams(), expression.getBody(), true, expression.isExpressionBody(),
                expression.isAsync(), false, env, expression.getSourceText());
    }

    public JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure, String sourceText) {
        final var function = new JsFunction(name, params, body, arrow, expressionBody, async, generator, closure);
        function.setSourceText(sourceText);
        // The module a frame is labelled with is where the function was written, not where it is called
        // from, so it is recorded once here rather than read off the stack at call time.
        function.setModuleName(callStack.currentModule());
        if (generator) {
            function.getPrototype().setProto(async ? intrinsics.asyncIteratorProto() : intrinsics.iteratorProto());
        }
        return function;
    }

    private JsValue evalCall(CallExpression call, Environment env) {
        final var callee = call.getCallee();
        if (callee instanceof SuperExpression) {
            return classes.evalSuperCall(call, env);
        }
        var thisArg = (JsValue) JsUndefined.getInstance();
        final JsValue function;
        if (callee instanceof MemberExpression member) {
            if (member.getObject() instanceof SuperExpression) {
                return classes.evalSuperMemberCall(member, call, env);
            }
            final var object = evalChainObject(member.getObject(), env);
            if (object == SHORT_CIRCUIT) {
                return SHORT_CIRCUIT;
            }
            if (member.isOptional() && isNullish(object)) {
                return SHORT_CIRCUIT;
            }
            thisArg = object;
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                function = getPrivateMember(object, priv.getName(), env);
            } else {
                function = getMemberByKey(object, memberKeyValue(member, env));
            }
        } else {
            final var callable = evalChainObject(callee, env);
            if (callable == SHORT_CIRCUIT) {
                return SHORT_CIRCUIT;
            }
            function = callable;
        }
        if (call.isOptional() && isNullish(function)) {
            return SHORT_CIRCUIT;
        }
        return callValue(function, thisArg, evalArguments(call.getArguments(), env));
    }

    private JsValue evalNew(NewExpression expression, Environment env) {
        final var callee = eval(expression.getCallee(), env);
        final var args = evalArguments(expression.getArguments(), env);
        return constructValue(callee, args);
    }

    private JsValue constructValue(JsValue callee, List<JsValue> args) {
        return constructValue(callee, args, callee);
    }

    public JsValue construct(JsValue callee, List<JsValue> args, JsValue newTarget) {
        return constructValue(callee, args, newTarget);
    }

    private JsValue constructValue(JsValue callee, List<JsValue> args, JsValue newTarget) {
        if (!isConstructor(callee)) {
            throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
        }
        return switch (callee) {
            case JsProxy proxy -> proxies.construct(proxy, args, newTarget);
            case JsClass cls -> classes.construct(cls, args, newTarget);
            case JsNativeFunction nativeFunction when nativeFunction.isBound() ->
                constructValue(nativeFunction.getBoundTarget(), boundArgs(nativeFunction, args),
                        newTarget == callee ? nativeFunction.getBoundTarget() : newTarget);
            case JsNativeFunction nativeFunction -> constructNative(nativeFunction, args, newTarget);
            case JsFunction function -> constructFunction(function, args, newTarget);
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
        };
    }

    private JsValue constructNative(JsNativeFunction nativeFunction, List<JsValue> args, JsValue newTarget) {
        final var proto = nativeFunction.getPrototype();
        if (proto == intrinsics.objectProto()) {
            // Object ( [ value ] ) step 1: when NewTarget is neither undefined nor the active
            // function (i.e. a subclass constructed this, directly or via Reflect.construct), the
            // value argument is ignored entirely and only OrdinaryCreateFromConstructor runs.
            if (newTarget != nativeFunction) {
                final var created = new JsObject();
                created.setProto(protoFromNewTarget(newTarget, proto));
                return created;
            }
            final var argument = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (isNullish(argument)) {
                final var created = new JsObject();
                created.setProto(proto);
                return created;
            }
            return intrinsics.toObject(argument);
        }
        if (proto == intrinsics.stringProto() || proto == intrinsics.numberProto()
                || proto == intrinsics.booleanProto()) {
            // String(sym) describes the symbol, but `new String(sym)` is still a TypeError, so the
            // wrapper branch must reject it before reaching the String function itself.
            if (proto == intrinsics.stringProto() && !args.isEmpty() && args.getFirst() instanceof JsSymbol) {
                throw new TypeErrorException("Cannot convert a Symbol value to a string");
            }
            return intrinsics.wrapPrimitive(nativeFunction.invoke(JsUndefined.getInstance(), args),
                    protoFromNewTarget(newTarget, proto));
        }
        return nativeFunction.invoke(JsUndefined.getInstance(), args, newTarget);
    }

    // OrdinaryCreateFromConstructor: the instance prototype is an ordinary Get(newTarget,
    // "prototype"), so an accessor-valued or throwing `prototype` behaves like any other property
    // read, and a non-object result falls back to the intrinsic default.
    private JsValue protoFromNewTarget(JsValue newTarget, JsValue fallback) {
        if (newTarget == null || isNullish(newTarget)) {
            return fallback;
        }
        final var proto = getMemberByKey(newTarget, new JsString("prototype"));
        if (isObjectLike(proto)) {
            return proto;
        }
        // GetFunctionRealm: a revoked proxy has no realm to fall back to, so the "prototype" read
        // observing the revocation (a `get` trap that revokes as a side effect, e.g.) must surface as
        // a TypeError instead of silently defaulting.
        if (newTarget instanceof JsProxy proxy && proxy.isRevoked()) {
            throw new TypeErrorException("Cannot perform 'get' on a proxy that has been revoked");
        }
        return fallback;
    }

    private List<JsValue> boundArgs(JsNativeFunction nativeFunction, List<JsValue> args) {
        final var combined = new ArrayList<>(nativeFunction.getBoundArgs());
        combined.addAll(args);
        return combined;
    }

    private JsValue constructFunction(JsFunction function, List<JsValue> args, JsValue newTarget) {
        final var instance = new JsObject();
        instance.setProto(protoFromNewTarget(newTarget, intrinsics.objectProto()));
        final var result = callFunction(function, instance, args, newTarget);
        return isObjectLike(result) ? result : instance;
    }

    public List<JsValue> evalArguments(List<Expression> arguments, Environment env) {
        final var values = new ArrayList<JsValue>();
        for (final var argument : arguments) {
            if (argument instanceof SpreadElement spread) {
                expressions.spreadInto(values, eval(spread.getArgument(), env));
            } else {
                values.add(eval(argument, env));
            }
        }
        return values;
    }

    public JsValue callValue(JsValue callee, JsValue thisArg, List<JsValue> args) {
        tick();
        return switch (callee) {
            case JsProxy proxy -> proxies.apply(proxy, thisArg, args);
            case JsFunction function -> callFunction(function, thisArg, args);
            case JsNativeFunction nativeFunction -> invokeNative(nativeFunction, thisArg, args);
            // %Function.prototype% is itself callable per spec (accepts any arguments, returns
            // undefined) despite being an ordinary JsObject rather than a JsFunction/JsNativeFunction,
            // since it must stay exposed as a JsObject for GlobalScope/getMember call sites.
            case JsObject object when object == intrinsics.functionProto() -> JsUndefined.getInstance();
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a function");
        };
    }

    private JsValue invokeNative(JsNativeFunction nativeFunction, JsValue thisArg, List<JsValue> args) {
        callStack.push(nativeFunction.getName(), CallStack.NATIVE_MODULE);
        try {
            return nativeFunction.invoke(thisArg, args);
        } finally {
            callStack.pop();
        }
    }

    private JsValue driveAsyncGenerator(JsAsyncGenerator generator, MemberEvaluator.AsyncStep step, JsValue argument) {
        return members.driveAsyncGenerator(generator, step, argument);
    }

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args) {
        return callFunction(function, thisArg, args, JsUndefined.getInstance());
    }

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        if (maxDepth >= 0 && depth >= maxDepth) {
            throw new ScriptLimitException("Script exceeded its maximum call depth");
        }
        depth++;
        callStack.push(function.getName(), function.getModuleName());
        try {
            final var activation = function.getClosure().functionChild();
            if (!function.isArrow()) {
                if (function.isDerivedConstructor()) {
                    activation.defineThisUninitialized(thisArg);
                } else {
                    activation.defineThis(thisArg);
                }
                activation.defineNewTarget(newTarget);
                activation.declareFunction("arguments", makeArguments(args));
            }
            // An async function's parameter list runs inside the returned promise, so a throwing
            // default rejects it instead of escaping to the caller synchronously.
            if (function.isAsync() && !function.isGenerator()) {
                return runAsync(function, activation, args);
            }
            binding.bindParams(function.getParams(), args, activation);
            if (function.isAsync()) {
                return makeAsyncGenerator(function, activation);
            }
            if (function.isGenerator()) {
                return makeGenerator(function, activation);
            }
            final var result = runPlainFunction(function, activation);
            if (function.isDerivedConstructor()) {
                // A derived constructor may only return an object or undefined, and the type check
                // comes before the `this`-was-initialised one. An explicit object return (step 13a
                // of [[Construct]]) short-circuits before `this` is ever consulted, so returning an
                // object without calling super() is legal; only an undefined (implicit or bare
                // `return;`) result needs `this` to have been initialised.
                if (!isObjectLike(result) && !(result instanceof JsUndefined)) {
                    throw new TypeErrorException("Derived constructors may only return object or undefined");
                }
                if (!isObjectLike(result) && !activation.isThisInitialized()) {
                    throw new ReferenceErrorException(
                            "Must call super constructor before returning from a derived class constructor");
                }
                // [[Construct]] step 13: an implicit/undefined return yields the constructor
                // environment's `this` binding (whatever super() bound it to - a base constructor
                // that returned an object overrides the freshly allocated instance), not the literal
                // completion value of the body.
                return isObjectLike(result) ? result : activation.resolveThis();
            }
            return result;
        } finally {
            callStack.pop();
            depth--;
        }
    }

    private JsValue runPlainFunction(JsFunction function, Environment activation) {
        final var saved = currentCoroutine.get();
        currentCoroutine.remove();
        try {
            return runFunctionBody(function, activation);
        } finally {
            if (saved != null) {
                currentCoroutine.set(saved);
            } else {
                currentCoroutine.remove();
            }
        }
    }

    private JsValue runFunctionBody(JsFunction function, Environment activation) {
        if (function.isExpressionBody()) {
            return eval((Expression) function.getBody(), activation);
        }
        final var body = (BlockStatement) function.getBody();
        final var bodyEnv = bodyEnvironment(function, activation, body);
        hoist(body.getBody(), bodyEnv);
        final var completion = statements.blockDeclaresUsing(body.getBody())
                ? statements.runDisposing(bodyEnv, () -> statements.execStatements(body.getBody(), bodyEnv))
                : statements.execStatements(body.getBody(), bodyEnv);
        return completion.kind() == Completion.Kind.RETURN ? completion.value() : JsUndefined.getInstance();
    }

    // FunctionDeclarationInstantiation step 27: with parameter expressions the body gets its own
    // variable environment, so a closure created in a parameter default never sees the body's `var`
    // declarations. A body `var` that shadows a parameter is *initialised from* that parameter rather
    // than aliasing it. A simple parameter list keeps one environment - which is also exactly when
    // `arguments` is mapped onto the parameter bindings.
    private Environment bodyEnvironment(JsFunction function, Environment activation, BlockStatement body) {
        if (!hasParameterExpressions(function.getParams())) {
            VarHoisting.hoistVars(body.getBody(), activation);
            return activation;
        }
        final var bodyEnv = activation.functionChild();
        for (final var name : VarHoisting.varNames(body.getBody())) {
            if (bodyEnv.hasLocal(name)) {
                continue;
            }
            bodyEnv.declareVar(name);
            if (activation.hasLocal(name)) {
                bodyEnv.assign(name, activation.get(name));
            }
        }
        return bodyEnv;
    }

    private static boolean hasParameterExpressions(List<JsNode> params) {
        for (final var param : params) {
            if (!(param instanceof Identifier)) {
                return true;
            }
        }
        return false;
    }

    private JsValue makeGenerator(JsFunction function, Environment activation) {
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markGenerator();
        ownStackSegment(coroutine, function);
        coroutine.prime(() -> {
            currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        final var generator = new JsGenerator(coroutine);
        final var proto = function.getPrototype();
        generator.setProto(proto instanceof JsObject object ? object : intrinsics.iteratorProto());
        return generator;
    }

    private JsValue runAsync(JsFunction function, Environment activation, List<JsValue> args) {
        final var promise = new JsPromise(eventLoop);
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markAsync();
        ownStackSegment(coroutine, function);
        coroutine.startAsync(() -> {
            currentCoroutine.set(coroutine);
            try {
                binding.bindParams(function.getParams(), args, activation);
                promise.resolve(runFunctionBody(function, activation));
            } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                    | SyntaxErrorException error) {
                promise.reject(toErrorValue(error, intrinsics));
            }
            return JsUndefined.getInstance();
        });
        return promise;
    }

    private JsValue makeAsyncGenerator(JsFunction function, Environment activation) {
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        final var generator = new JsAsyncGenerator(coroutine);
        coroutine.markAsync();
        coroutine.markGenerator();
        ownStackSegment(coroutine, function);
        coroutine.setResumeObserver(escaped -> members.observeAsyncGenerator(generator, escaped));
        coroutine.prime(() -> {
            currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        final var proto = function.getPrototype();
        generator.setProto(proto instanceof JsObject object ? object : intrinsics.asyncIteratorProto());
        return generator;
    }

    // A coroutine body resumes and parks many times, interleaved with its consumer's own code, so it gets
    // its own stack segment installed for the length of each resumption rather than sharing the caller's.
    private void ownStackSegment(Coroutine coroutine, JsFunction function) {
        final var segment = callStack.segmentFor(function.getName(), function.getModuleName());
        coroutine.setAroundResume(resume -> {
            final var saved = callStack.swap(segment);
            try {
                resume.run();
            } finally {
                callStack.swap(saved);
            }
        });
    }

    private JsValue evalYield(YieldExpression yield, Environment env) {
        final var coroutine = currentCoroutine.get();
        if (coroutine == null || !coroutine.isYieldAllowed()) {
            throw new SyntaxErrorException("yield is only valid inside a generator");
        }
        if (yield.isDelegate()) {
            return YieldDelegation.run(this, coroutine, eval(yield.getArgument(), env));
        }
        final var value = yield.getArgument() == null ? JsUndefined.getInstance() : eval(yield.getArgument(), env);
        return coroutine.yieldOut(value);
    }

    private JsValue evalAwait(AwaitExpression await, Environment env) {
        final var coroutine = currentCoroutine.get();
        if (coroutine == null || !coroutine.isAsync()) {
            throw new SyntaxErrorException("await is only valid inside an async function");
        }
        return coroutine.await(toPromise(eval(await.getArgument(), env)));
    }

    public JsPromise toPromise(JsValue value) {
        if (value instanceof JsPromise promise) {
            // PromiseResolve(%Promise%, x): even though x is already a promise, its "constructor" is
            // still read for the SameValue(xConstructor, C) check - an observable Get whose result is
            // otherwise unused here (this engine has one Promise implementation, so x is always
            // returned as-is), but a throwing accessor must still propagate.
            getMemberByKey(promise, new JsString("constructor"));
            return promise;
        }
        final var promise = new JsPromise(eventLoop);
        promise.resolve(value);
        return promise;
    }

    // CreateMappedArgumentsObject only ever applies to sloppy-mode function code with a simple
    // parameter list; this engine is always-strict (no sloppy mode exists at all - see CLAUDE.md/
    // docs/simplejs.md), so `arguments` must always be the unmapped form, never aliasing a named
    // parameter regardless of how simple the parameter list looks.
    private JsArguments makeArguments(List<JsValue> args) {
        return withOwnProperties(new JsArguments(args, null, null));
    }

    // CreateUnmappedArgumentsObject's non-index properties: "callee" is the poison-pill accessor pair
    // (non-configurable, unlike a function's own poisoned callee) and @@iterator is %Array.prototype.values%.
    private JsArguments withOwnProperties(JsArguments arguments) {
        intrinsics.poison(arguments, "callee");
        final var table = arguments.ownProperties();
        table.setFlags("callee", new JsObject.PropertyFlags(false, false, false));
        table.defineSymbolValue(JsSymbol.ITERATOR, intrinsics.arrayProto().getSymbol(JsSymbol.ITERATOR));
        table.setSymbolFlags(JsSymbol.ITERATOR, new JsObject.PropertyFlags(true, false, true));
        return arguments;
    }

    public void destructureAssignment(JsNode target, JsValue value, Environment env) {
        binding.destructureAssignment(target, value, env);
    }

    public JsValue getStaticMember(JsClass cls, String key) {
        if ("prototype".equals(key)) {
            return cls.getPrototype();
        }
        if ("name".equals(key) && !cls.hasStaticProp(key)) {
            return new JsString(cls.getName() == null ? "" : cls.getName());
        }
        final var getter = cls.findStaticGetter(key);
        if (getter != null) {
            return callFunction(getter, cls, List.of());
        }
        final var method = cls.findStaticMethod(key);
        if (method != null) {
            return method;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return current.getStaticProp(key);
            }
        }
        // A class constructor's [[Prototype]] is its heritage, so a builtin superclass's statics
        // (Promise.resolve on `class P extends Promise`) are inherited by the subclass constructor.
        final var nativeSuper = cls.findNativeSuperClass();
        if (nativeSuper != null) {
            return getMember(nativeSuper, key);
        }
        // Otherwise the class object is an ordinary function object, so its [[Prototype]] chain ends
        // at Function.prototype and Object.prototype - `C.hasOwnProperty(…)` has to resolve there.
        return getMember(intrinsics.functionProto(), key);
    }

    // Last-resort identifier lookup for a name Environment has no binding for at all: the Global
    // Environment Record's object-record half answers via HasProperty/Get(globalObj, name), which
    // reaches a globalThis-only accessor (added via Object.defineProperty/defineProperties, never a
    // declared var/function binding) that Environment.isDeclared cannot see. Returns null - not
    // JsUndefined - when the global object has no such own property either, so the caller can still
    // tell "genuinely unresolvable" apart from "resolves to undefined".
    public JsValue globalPropertyValue(String name) {
        if (globalObjectValue == null) {
            return null;
        }
        final var descriptor = globalObjectValue.getOwnProperty(new JsString(name));
        if (descriptor == null) {
            return null;
        }
        if (descriptor.isAccessorDescriptor()) {
            final var getter = descriptor.getter();
            return getter == null || getter instanceof JsUndefined
                    ? JsUndefined.getInstance()
                    : callValue(getter, globalObjectValue, List.of());
        }
        return descriptor.value();
    }

    public JsValue getPrivateMember(JsValue target, String name, Environment env) {
        final var owner = env.resolvePrivateClass(name);
        final var privateName = owner == null ? null : owner.privateNameFor(name);
        if (owner != null && target == owner && owner.declaresStaticPrivate(privateName)) {
            final var getter = owner.getPrivateStaticGetter(privateName);
            if (getter != null) {
                return callFunction(getter, owner, List.of());
            }
            final var method = owner.getPrivateStaticMethod(privateName);
            if (method != null) {
                return method;
            }
            if (owner.hasPrivateStaticField(privateName)) {
                return owner.getPrivateStaticField(privateName);
            }
        }
        final var object = owner == null ? null : classes.resolvePrivateStorage(target);
        if (object != null) {
            if (object.hasPrivate(privateName)) {
                return object.getPrivate(privateName);
            }
            final var getter = owner.getPrivateInstanceGetter(privateName);
            final var method = owner.getPrivateInstanceMethod(privateName);
            requirePrivateBrand(object, owner, name, getter != null || method != null);
            if (getter != null) {
                // PrivateFieldGet calls the accessor with the original base (a Proxy is invoked as
                // itself, not as the unwrapped storage object it was resolved through).
                return callFunction(getter, target, List.of());
            }
            if (method != null) {
                return method;
            }
        }
        throw new TypeErrorException(
                "Cannot read private member #" + name + " from an object whose class did not declare it");
    }

    public void setPrivateMember(JsValue target, String name, JsValue value, Environment env) {
        final var owner = env.resolvePrivateClass(name);
        final var privateName = owner == null ? null : owner.privateNameFor(name);
        if (owner != null && target == owner && owner.declaresStaticPrivate(privateName)) {
            final var setter = owner.getPrivateStaticSetter(privateName);
            if (setter != null) {
                callFunction(setter, owner, List.of(value));
                return;
            }
            if (owner.getPrivateStaticMethod(privateName) != null
                    || owner.getPrivateStaticGetter(privateName) != null) {
                throw new TypeErrorException("Cannot write to private member #" + name + ", it has no setter");
            }
            if (owner.hasPrivateStaticField(privateName)) {
                owner.setPrivateStaticField(privateName, value);
                return;
            }
        }
        final var object = owner == null ? null : classes.resolvePrivateStorage(target);
        if (object != null) {
            final var setter = owner.getPrivateInstanceSetter(privateName);
            final var readOnly = owner.getPrivateInstanceGetter(privateName) != null
                    || owner.getPrivateInstanceMethod(privateName) != null;
            requirePrivateBrand(object, owner, name, setter != null || readOnly);
            if (setter != null) {
                // PrivateFieldSet calls the accessor with the original base, same rationale as the
                // getter path above.
                callFunction(setter, target, List.of(value));
                return;
            }
            if (readOnly) {
                throw new TypeErrorException("Cannot write to private member #" + name + ", it has no setter");
            }
            if (object.hasPrivate(privateName)) {
                object.setPrivate(privateName, value);
                return;
            }
        }
        throw new TypeErrorException(
                "Cannot write private member #" + name + " to an object whose class did not declare it");
    }

    // A private method or accessor lives on the class, not the instance, so the only thing that makes
    // it reachable through a given object is the brand installed when that object was initialized as
    // an instance of the declaring class.
    private static void requirePrivateBrand(JsObject object, JsClass cls, String name, boolean declared) {
        if (declared && !object.hasPrivateBrand(cls)) {
            throw new TypeErrorException(
                    "Cannot access private member #" + name + " from an object whose class did not declare it");
        }
    }

    public JsValue assignToPrivate(MemberExpression member, PrivateIdentifier priv, AssignmentExpression assignment,
            Environment env) {
        final var target = eval(member.getObject(), env);
        final var name = priv.getName();
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = eval(assignment.getValue(), env);
            setPrivateMember(target, name, value, env);
            return value;
        }
        final var current = getPrivateMember(target, name, env);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = eval(assignment.getValue(), env);
            setPrivateMember(target, name, value, env);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env), ops);
        setPrivateMember(target, name, value, env);
        return value;
    }

    // [[OwnPropertyKeys]] is the value's own protocol method, so Reflect.ownKeys and
    // Object.getOwnPropertyNames cannot report different key sets for the same value.
    private List<JsValue> ownKeysOf(JsValue target) {
        return target instanceof JsProxy proxy ? proxies.ownKeys(proxy) : new ArrayList<>(target.ownPropertyKeys());
    }

    private boolean deleteMemberValue(JsValue target, JsValue rawKey) {
        final var keyValue = JsCoercion.toPropertyKey(rawKey, ops);
        if (target instanceof JsProxy proxy) {
            return proxies.delete(proxy, keyValue);
        }
        // Mirrors ExpressionEvaluator.evalDelete's JsArray arm: the generic JsValue.deleteOwnProperty
        // does not know "length" is special, so a delete reaching an array through this path (e.g. a
        // no-trap Proxy forwarding to an array target) must go through the same array-aware helper or
        // it would let a non-configurable "length" be deleted.
        if (target instanceof JsArray array && !(keyValue instanceof JsSymbol)) {
            return deleteArrayElement(array, JsCoercion.toStr(keyValue));
        }
        return target.deleteOwnProperty(keyValue);
    }

    private List<JsValue> iterableToList(JsValue iterable) {
        final var result = new ArrayList<JsValue>();
        final var iteration = new Iteration(this, iterable);
        var element = iteration.next();
        while (element != null) {
            result.add(element);
            element = iteration.next();
        }
        return result;
    }
}
