package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.host.CancellationToken;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.interpreter.BindingEvaluator;
import org.techhouse.simplejs.internal.interpreter.CallStack;
import org.techhouse.simplejs.internal.interpreter.ClassEvaluator;
import org.techhouse.simplejs.internal.interpreter.ConstructEvaluator;
import org.techhouse.simplejs.internal.interpreter.ExpressionEvaluator;
import org.techhouse.simplejs.internal.interpreter.FunctionInvoker;
import org.techhouse.simplejs.internal.interpreter.HasMemberEvaluator;
import org.techhouse.simplejs.internal.interpreter.InterpreterOpsBridge;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.internal.interpreter.MemberAccessEvaluator;
import org.techhouse.simplejs.internal.interpreter.MemberEvaluator;
import org.techhouse.simplejs.internal.interpreter.ModuleEvaluator;
import org.techhouse.simplejs.internal.interpreter.ModuleLifecycle;
import org.techhouse.simplejs.internal.interpreter.ModuleRegistry;
import org.techhouse.simplejs.internal.interpreter.PrivateMemberEvaluator;
import org.techhouse.simplejs.internal.interpreter.ProxyDispatch;
import org.techhouse.simplejs.internal.interpreter.StackCapture;
import org.techhouse.simplejs.internal.interpreter.StatementEvaluator;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsValue;

public final class Interpreter {
    public static final JsValue SHORT_CIRCUIT = new JsValue() {
    };

    public record ProgramOutcome(JsValue lastValue, boolean hasReturn, JsValue returnValue, JsValue exportDefault,
            Map<String, JsValue> namedExports) {
    }

    public final EventLoop eventLoop = new EventLoop();
    public final ThreadLocal<Coroutine> currentCoroutine = new ThreadLocal<>();
    public final List<Coroutine> coroutines = new ArrayList<>();
    public final InterpreterOps ops = new InterpreterOpsBridge(this);
    public final ProxyDispatch proxies = new ProxyDispatch(ops);
    public final Intrinsics intrinsics = new Intrinsics(this::callValue, ops, eventLoop, this::driveAsyncGenerator);
    public final MemberEvaluator members = new MemberEvaluator(this, eventLoop, proxies);
    public final BindingEvaluator binding = new BindingEvaluator(this, members);
    public final ClassEvaluator classes = new ClassEvaluator(this);
    public final StatementEvaluator statements = new StatementEvaluator(this, members, proxies);
    public final ExpressionEvaluator expressions = new ExpressionEvaluator(this, classes, proxies);
    public final ModuleEvaluator modules;

    public final HostBindings host;
    public final ModuleRegistry moduleRegistry = new ModuleRegistry();
    public final int maxDepth;
    public final int maxModuleDepth;
    public long instructionsRemaining;
    public long instructionsUsed;
    public long peakBytesUsed;
    public long bytesRemaining;
    public final long memoryBudget;
    public final long deadlineNanos;
    public final CancellationToken cancellation;
    public int depth;
    public int moduleDepth;
    public final CallStack callStack = new CallStack();
    public Environment globalEnv;
    public ModuleBodyWrapper moduleBodyWrapper;
    public JsGlobalObject globalObjectValue;

    public final HasMemberEvaluator hasMembers = new HasMemberEvaluator(this);
    public final PrivateMemberEvaluator privateMembers = new PrivateMemberEvaluator(this);
    public final ConstructEvaluator constructs = new ConstructEvaluator(this);
    public final FunctionInvoker functions = new FunctionInvoker(this);

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args) {
        return functions.callFunction(function, thisArg, args);
    }

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        return functions.callFunction(function, thisArg, args, newTarget);
    }

    public JsValue callValue(JsValue callee, JsValue thisArg, List<JsValue> args) {
        return functions.callValue(callee, thisArg, args);
    }

    public List<JsValue> evalArguments(List<Expression> arguments, Environment env) {
        return functions.evalArguments(arguments, env);
    }

    public JsPromise toPromise(JsValue value) {
        return functions.toPromise(value);
    }

    public JsValue construct(JsValue callee, List<JsValue> args, JsValue newTarget) {
        return constructs.construct(callee, args, newTarget);
    }

    public JsValue getPrivateMember(JsValue target, String name, Environment env) {
        return privateMembers.getPrivateMember(target, name, env);
    }

    public void setPrivateMember(JsValue target, String name, JsValue value, Environment env) {
        privateMembers.setPrivateMember(target, name, value, env);
    }

    public JsValue assignToPrivate(MemberExpression member, PrivateIdentifier priv, AssignmentExpression assignment,
            Environment env) {
        return privateMembers.assignToPrivate(member, priv, assignment, env);
    }

    public boolean hasMember(JsValue container, JsValue keyValue) {
        return hasMembers.hasMember(container, keyValue);
    }

    public final ModuleLifecycle moduleLifecycle = new ModuleLifecycle(this);
    public final MemberAccessEvaluator memberAccess = new MemberAccessEvaluator(this);

    public JsValue getStaticMember(JsClass cls, String key) {
        return memberAccess.getStaticMember(cls, key);
    }

    public JsValue globalPropertyValue(String name) {
        return memberAccess.globalPropertyValue(name);
    }

    public JsValue memberKeyValue(MemberExpression member, Environment env) {
        return memberAccess.memberKeyValue(member, env);
    }

    public String memberKey(MemberExpression member, Environment env) {
        return memberAccess.memberKey(member, env);
    }

    public JsValue importModule(String moduleId, String displayName, Supplier<Program> parser) {
        return moduleLifecycle.importModule(moduleId, displayName, parser);
    }

    public JsValue cacheBuiltinModule(String moduleId, Supplier<JsValue> factory) {
        return moduleLifecycle.cacheBuiltinModule(moduleId, factory);
    }

    public JsValue referenceKey(JsValue target, JsValue rawKey) {
        return memberAccess.referenceKey(target, rawKey);
    }

    public void reportUnhandledRejections() {
        moduleLifecycle.reportUnhandledRejections();
    }

    public ProgramOutcome evaluateTopLevelModule(Program program) {
        return moduleLifecycle.evaluateTopLevelModule(program);
    }

    final MemberIo memberIo = new MemberIo(this);

    final FunctionFactory functionFactory = new FunctionFactory(this);

    final EvalDispatch dispatch = new EvalDispatch(this);

    private final RunLifecycle lifecycle = new RunLifecycle(this);

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

    public static <T> T run(Program program, HostBindings host, ModuleBodyWrapper around, ResultFinisher<T> finisher) {
        return run(program, host, around, finisher, null);
    }

    public static <T> T run(Program program, HostBindings host, ModuleBodyWrapper around, ResultFinisher<T> finisher,
            RunMetrics metrics) {
        final var interpreter = new Interpreter(host);
        interpreter.moduleBodyWrapper = around;
        return interpreter.runModule(program, finisher, metrics);
    }

    @FunctionalInterface
    public interface ResultFinisher<T> {
        T finish(ProgramOutcome outcome, InterpreterOps ops);
    }

    public static final class RunMetrics {
        private long instructions;
        private long instructionBudget = -1;
        private long peakMemoryBytes;
        private long memoryBudget = -1;

        void fill(Interpreter interpreter) {
            instructions = interpreter.instructionsUsed;
            instructionBudget = interpreter.host.limits().instructionBudget();
            peakMemoryBytes = interpreter.peakBytesUsed;
            memoryBudget = interpreter.memoryBudget;
        }

        public long instructions() {
            return instructions;
        }

        public long instructionBudget() {
            return instructionBudget;
        }

        public long peakMemoryBytes() {
            return peakMemoryBytes;
        }

        public long memoryBudget() {
            return memoryBudget;
        }
    }

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

    <T> T runModule(Program program, ResultFinisher<T> finisher) {
        return runModule(program, finisher, null);
    }

    <T> T runModule(Program program, ResultFinisher<T> finisher, RunMetrics metrics) {
        StackCapture.install(callStack);
        try {
            final var outcome = moduleLifecycle.evaluateTopLevelModule(program);
            final var finished = finisher.finish(outcome, ops);
            eventLoop.drain(deadlineNanos);
            moduleLifecycle.reportUnhandledRejections();
            return finished;
        } finally {
            if (metrics != null) {
                metrics.fill(this);
            }
            lifecycle.cancelPendingCoroutines();
            StackCapture.uninstall(callStack);
        }
    }

    public static Session open(Program program, HostBindings host) {
        final var interpreter = new Interpreter(host);
        StackCapture.install(interpreter.callStack);
        try {
            return new Session(interpreter, interpreter.evaluateTopLevelModule(program));
        } catch (RuntimeException | Error failure) {
            interpreter.reportUnhandledRejections();
            interpreter.cancelPendingCoroutines();
            throw failure;
        } finally {
            StackCapture.uninstall(interpreter.callStack);
        }
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

    private JsValue driveAsyncGenerator(JsAsyncGenerator generator, MemberEvaluator.AsyncStep step, JsValue argument) {
        return members.driveAsyncGenerator(generator, step, argument);
    }

    public void destructureAssignment(JsNode target, JsValue value, Environment env) {
        binding.destructureAssignment(target, value, env);
    }

    public List<JsValue> iterableToList(JsValue iterable) {
        final var result = new ArrayList<JsValue>();
        final var iteration = new Iteration(this, iterable);
        var element = iteration.next();
        while (element != null) {
            result.add(element);
            element = iteration.next();
        }
        return result;
    }
    public JsValue eval(Expression expression, Environment env) {
        return dispatch.eval(expression, env);
    }

    public JsValue evalNamed(Expression expression, Environment env, String name) {
        return dispatch.evalNamed(expression, env, name);
    }

    public Completion evalStatement(Statement statement, Environment env) {
        return dispatch.evalStatement(statement, env);
    }

    public void hoist(List<Statement> body, Environment env) {
        dispatch.hoist(body, env);
    }

    public JsValue getMemberByKey(JsValue target, JsValue keyValue) {
        return memberIo.getMemberByKey(target, keyValue);
    }

    public JsValue getMemberByKey(JsValue target, JsValue keyValue, JsValue receiver) {
        return memberIo.getMemberByKey(target, keyValue, receiver);
    }

    public boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value) {
        return memberIo.setMemberByKey(target, rawKey, value);
    }

    public boolean setMemberByKey(JsValue target, JsValue rawKey, JsValue value, JsValue receiver) {
        return memberIo.setMemberByKey(target, rawKey, value, receiver);
    }

    public JsValue getMember(JsValue target, String key) {
        return memberIo.getMember(target, key);
    }

    public JsValue evalCall(CallExpression call, Environment env) {
        return functionFactory.evalCall(call, env);
    }

    public JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure, String sourceText) {
        return functionFactory.makeFunction(name, params, body, arrow, expressionBody, async, generator, closure,
                sourceText);
    }

    public void tick() {
        lifecycle.tick();
    }

    public void charge(long bytes) {
        lifecycle.charge(bytes);
    }

    public void release(long bytes) {
        lifecycle.release(bytes);
    }

    public void cancelPendingCoroutines() {
        lifecycle.cancelPendingCoroutines();
    }

    public JsValue evalProgram(Program program) {
        return lifecycle.evalProgram(program);
    }
}
