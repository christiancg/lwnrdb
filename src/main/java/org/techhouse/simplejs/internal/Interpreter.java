package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.ObjectBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.interpreter.BindingEvaluator;
import org.techhouse.simplejs.internal.interpreter.ClassEvaluator;
import org.techhouse.simplejs.internal.interpreter.ExpressionEvaluator;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.internal.interpreter.MemberEvaluator;
import org.techhouse.simplejs.internal.interpreter.ModuleEvaluator;
import org.techhouse.simplejs.internal.interpreter.ProxyDispatch;
import org.techhouse.simplejs.internal.interpreter.StatementEvaluator;
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
        public JsValue construct(JsValue fn, List<JsValue> args) {
            return constructValue(fn, args);
        }

        @Override
        public JsValue getPrototypeOf(JsValue target) {
            if (target instanceof JsProxy proxy) {
                return proxies.getPrototypeOf(proxy);
            }
            if (target instanceof JsObject || isNullish(target)) {
                return ObjectBuiltins.getPrototypeOf(List.of(target));
            }
            return intrinsics.protoFor(target);
        }

        @Override
        public boolean setPrototypeOf(JsValue target, JsValue proto) {
            if (target instanceof JsProxy proxy) {
                return proxies.setPrototypeOf(proxy, proto);
            }
            ObjectBuiltins.setPrototypeOf(List.of(target, proto));
            return true;
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
            if (target instanceof JsProxy proxy) {
                return proxies.defineProperty(proxy, key, descriptor);
            }
            ObjectBuiltins.defineProperty(List.of(target, key, descriptor));
            return true;
        }

        @Override
        public JsValue getOwnPropertyDescriptor(JsValue target, JsValue key) {
            return target instanceof JsProxy proxy
                    ? proxies.getOwnPropertyDescriptor(proxy, key)
                    : ObjectBuiltins.getOwnPropertyDescriptor(List.of(target, key));
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
    private final int maxDepth;
    private long instructionsRemaining;
    private final long deadlineNanos;
    private int depth;

    private Interpreter(HostBindings host) {
        this.host = host;
        this.modules = new ModuleEvaluator(this, classes, host, eventLoop);
        final var limits = host.limits();
        this.maxDepth = limits.maxDepth();
        this.instructionsRemaining = limits.instructionBudget();
        this.deadlineNanos = limits.wallClockMillis() > 0
                ? System.nanoTime() + limits.wallClockMillis() * 1_000_000L
                : -1;
    }

    public static JsValue run(Program program) {
        return new Interpreter(SimpleHostBindings.empty()).evalProgram(program);
    }

    public static JsValue run(String source) {
        return run(Parser.parse(Lexer.lexWithPositions(source)));
    }

    public static ProgramOutcome run(Program program, HostBindings host) {
        return new Interpreter(host).runModule(program);
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
    }

    private JsValue evalProgram(Program program) {
        return runModule(program).lastValue();
    }

    private ProgramOutcome runModule(Program program) {
        final var env = Environment.global();
        GlobalScope.install(env, eventLoop, this::callValue, this::iterableToList, host.console(), ops, host.network(),
                host.limits(), intrinsics);
        for (final var statement : program.getBody()) {
            if (statement instanceof ImportDeclaration importDeclaration) {
                modules.bindImport(importDeclaration, env);
            }
        }
        hoist(program.getBody(), env);
        final var result = new ModuleResult();
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markAsync();
        try {
            coroutine.startAsync(() -> {
                currentCoroutine.set(coroutine);
                runModuleBody(program, env, result);
                return JsUndefined.getInstance();
            });
            markContractPromiseHandled(result);
            eventLoop.drain(deadlineNanos);
            reportUnhandledRejections();
        } finally {
            for (final var pending : coroutines) {
                if (!pending.isDone()) {
                    pending.cancel();
                }
            }
        }
        return new ProgramOutcome(result.last, result.hasReturn, result.returnValue, result.exportDefault,
                result.namedExports);
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

    private void runModuleBody(Program program, Environment env, ModuleResult result) {
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
                        declaration.isAsync(), declaration.isGenerator(), env);
                env.declareFunction(name, function);
            }
        }
    }

    public Completion evalStatement(Statement statement, Environment env) {
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
            case JsProxy proxy -> proxies.has(proxy, keyValue);
            case JsGlobalObject global -> global.getEnv().isDeclared(JsCoercion.toStr(keyValue));
            case JsObject object when keyValue instanceof JsSymbol symbol -> hasSymbolMember(object, symbol);
            case JsObject object -> object.has(JsCoercion.toStr(keyValue));
            case JsArray array -> arrayHasMember(array, JsCoercion.toStr(keyValue));
            default -> throw new TypeErrorException(
                    "Cannot use 'in' operator to search for '" + JsCoercion.toStr(keyValue) + "'");
        };
    }

    private boolean hasSymbolMember(JsObject object, JsSymbol symbol) {
        for (var current = object; current != null; current = current.getProto()) {
            if (current.hasSymbol(symbol)) {
                return true;
            }
        }
        return false;
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
            return JsCoercion.toStr(eval(member.getProperty(), env));
        }
        if (member.getProperty() instanceof Identifier id) {
            return id.getName();
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    public JsValue memberKeyValue(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return eval(member.getProperty(), env);
        }
        if (member.getProperty() instanceof Identifier id) {
            return new JsString(id.getName());
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    public JsValue getMemberByKey(JsValue target, JsValue keyValue) {
        if (target instanceof JsProxy proxy) {
            return proxies.get(proxy, keyValue);
        }
        if (keyValue instanceof JsSymbol symbol) {
            return members.getSymbolMember(target, symbol);
        }
        return members.getMember(target, JsCoercion.toStr(keyValue));
    }

    public JsValue getMemberByKey(JsValue target, JsValue keyValue, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            return proxies.get(proxy, keyValue);
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

    public Coroutine currentCoroutine() {
        return currentCoroutine.get();
    }

    public boolean setMemberByKey(JsValue target, JsValue keyValue, JsValue value) {
        if (target instanceof JsProxy proxy) {
            proxies.set(proxy, keyValue, value);
            return true;
        }
        if (keyValue instanceof JsSymbol symbol) {
            if (target instanceof JsObject object) {
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
            return true;
        }
        return members.setMember(target, JsCoercion.toStr(keyValue), value);
    }

    public boolean setMemberByKey(JsValue target, JsValue keyValue, JsValue value, JsValue receiver) {
        if (target instanceof JsProxy proxy) {
            proxies.set(proxy, keyValue, value);
            return true;
        }
        if (keyValue instanceof JsSymbol) {
            return setMemberByKey(target, keyValue, value);
        }
        return members.setMember(target, JsCoercion.toStr(keyValue), value, receiver);
    }

    private JsValue evalFunctionExpression(FunctionExpression expression, Environment env) {
        final var name = expression.getName() == null ? null : expression.getName().getName();
        return makeFunction(name, expression.getParams(), expression.getBody(), false, false, expression.isAsync(),
                expression.isGenerator(), env);
    }

    private JsValue evalArrowFunction(ArrowFunctionExpression expression, Environment env) {
        return makeFunction(null, expression.getParams(), expression.getBody(), true, expression.isExpressionBody(),
                expression.isAsync(), false, env);
    }

    public JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure) {
        return new JsFunction(name, params, body, arrow, expressionBody, async, generator, closure);
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
        return switch (callee) {
            case JsProxy proxy -> proxies.construct(proxy, args);
            case JsClass cls -> classes.construct(cls, args);
            case JsNativeFunction nativeFunction when nativeFunction.isBound() ->
                constructValue(nativeFunction.getBoundTarget(), boundArgs(nativeFunction, args));
            case JsNativeFunction nativeFunction -> constructNative(nativeFunction, args);
            case JsFunction function when !function.isArrow() -> constructFunction(function, args);
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
        };
    }

    private JsValue constructNative(JsNativeFunction nativeFunction, List<JsValue> args) {
        final var proto = nativeFunction.getPrototype();
        if (proto == intrinsics.objectProto()) {
            final var argument = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (isObjectLike(argument)) {
                return argument;
            }
            final var created = new JsObject();
            created.setProto(proto);
            return created;
        }
        if (proto == intrinsics.stringProto() || proto == intrinsics.numberProto()
                || proto == intrinsics.booleanProto()) {
            final var wrapper = new JsObject();
            wrapper.setProto(proto);
            wrapper.setPrimitive(nativeFunction.invoke(JsUndefined.getInstance(), args));
            return wrapper;
        }
        return nativeFunction.invoke(JsUndefined.getInstance(), args);
    }

    private List<JsValue> boundArgs(JsNativeFunction nativeFunction, List<JsValue> args) {
        final var combined = new ArrayList<>(nativeFunction.getBoundArgs());
        combined.addAll(args);
        return combined;
    }

    private JsValue constructFunction(JsFunction function, List<JsValue> args) {
        final var instance = new JsObject();
        instance.setProto(function.getPrototype());
        final var result = callFunction(function, instance, args, function);
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
            case JsNativeFunction nativeFunction -> nativeFunction.invoke(thisArg, args);
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a function");
        };
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
        try {
            final var activation = function.getClosure().functionChild();
            if (!function.isArrow()) {
                activation.defineThis(thisArg);
                activation.defineNewTarget(newTarget);
                activation.declareFunction("arguments", makeArguments(function.getParams(), args, activation));
            }
            binding.bindParams(function.getParams(), args, activation);
            if (function.isAsync() && function.isGenerator()) {
                return makeAsyncGenerator(function, activation);
            }
            if (function.isGenerator()) {
                return makeGenerator(function, activation);
            }
            if (function.isAsync()) {
                return runAsync(function, activation);
            }
            return runPlainFunction(function, activation);
        } finally {
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
        hoist(body.getBody(), activation);
        final var completion = statements.blockDeclaresUsing(body.getBody())
                ? statements.runDisposing(activation, () -> statements.execStatements(body.getBody(), activation))
                : statements.execStatements(body.getBody(), activation);
        return completion.kind() == Completion.Kind.RETURN ? completion.value() : JsUndefined.getInstance();
    }

    private JsValue makeGenerator(JsFunction function, Environment activation) {
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markGenerator();
        coroutine.prime(() -> {
            currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        return new JsGenerator(coroutine);
    }

    private JsValue runAsync(JsFunction function, Environment activation) {
        final var promise = new JsPromise(eventLoop);
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        coroutine.markAsync();
        coroutine.startAsync(() -> {
            currentCoroutine.set(coroutine);
            try {
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
        coroutine.setResumeObserver(escaped -> members.observeAsyncGenerator(generator, escaped));
        coroutine.prime(() -> {
            currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        return generator;
    }

    private JsValue evalYield(YieldExpression yield, Environment env) {
        final var coroutine = currentCoroutine.get();
        if (coroutine == null || !coroutine.isYieldAllowed()) {
            throw new SyntaxErrorException("yield is only valid inside a generator");
        }
        if (yield.isDelegate()) {
            return yieldDelegate(coroutine, eval(yield.getArgument(), env));
        }
        final var value = yield.getArgument() == null ? JsUndefined.getInstance() : eval(yield.getArgument(), env);
        return coroutine.yieldOut(value);
    }

    private JsValue yieldDelegate(Coroutine coroutine, JsValue iterable) {
        if (iterable instanceof JsAsyncGenerator generator && coroutine.isAsync()) {
            return yieldDelegateAsync(coroutine, generator);
        }
        if (iterable instanceof JsGenerator generator) {
            final var inner = generator.getCoroutine();
            var sent = (JsValue) JsUndefined.getInstance();
            while (true) {
                final var step = inner.resumeNext(sent);
                if (step.done()) {
                    return step.value();
                }
                sent = coroutine.yieldOut(step.value());
            }
        }
        final var iteration = new Iteration(this, iterable);
        var value = iteration.next();
        while (value != null) {
            coroutine.yieldOut(value);
            value = iteration.next();
        }
        return JsUndefined.getInstance();
    }

    private JsValue yieldDelegateAsync(Coroutine coroutine, JsAsyncGenerator generator) {
        while (true) {
            final var step = coroutine.await(toPromise(
                    members.driveAsyncGenerator(generator, MemberEvaluator.AsyncStep.NEXT, JsUndefined.getInstance())));
            if (JsCoercion.toBoolean(members.getMember(step, "done"))) {
                return members.getMember(step, "value");
            }
            coroutine.yieldOut(members.getMember(step, "value"));
        }
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
            return promise;
        }
        final var promise = new JsPromise(eventLoop);
        promise.resolve(value);
        return promise;
    }

    private JsArguments makeArguments(List<JsNode> params, List<JsValue> args, Environment activation) {
        var mapped = true;
        for (final var param : params) {
            if (!(param instanceof Identifier)) {
                mapped = false;
                break;
            }
        }
        if (!mapped) {
            return new JsArguments(args, null, null);
        }
        final var names = new ArrayList<String>();
        for (final var param : params) {
            names.add(((Identifier) param).getName());
        }
        return new JsArguments(args, names, activation);
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
        return JsUndefined.getInstance();
    }

    public JsValue getPrivateMember(JsValue target, String name, Environment env) {
        if (target instanceof JsClass cls && declaresStaticPrivate(env, name)) {
            final var getter = cls.getPrivateStaticGetter(name);
            if (getter != null) {
                return callFunction(getter, cls, List.of());
            }
            final var method = cls.getPrivateStaticMethod(name);
            if (method != null) {
                return method;
            }
            if (cls.hasPrivateStaticField(name)) {
                return cls.getPrivateStaticField(name);
            }
        }
        if (target instanceof JsObject object) {
            if (object.hasPrivate(name)) {
                return object.getPrivate(name);
            }
            if (env.resolveHomeClass() instanceof JsClass cls) {
                final var getter = cls.getPrivateInstanceGetter(name);
                if (getter != null) {
                    return callFunction(getter, object, List.of());
                }
                final var method = cls.getPrivateInstanceMethod(name);
                if (method != null) {
                    return method;
                }
            }
        }
        throw new TypeErrorException(
                "Cannot read private member #" + name + " from an object whose class did not declare it");
    }

    private static boolean declaresStaticPrivate(Environment env, String name) {
        return env.resolveHomeClass() instanceof JsClass home && home.declaresStaticPrivate(name);
    }

    public void setPrivateMember(JsValue target, String name, JsValue value, Environment env) {
        if (target instanceof JsClass cls && declaresStaticPrivate(env, name)) {
            final var setter = cls.getPrivateStaticSetter(name);
            if (setter != null) {
                callFunction(setter, cls, List.of(value));
            } else {
                cls.setPrivateStaticField(name, value);
            }
            return;
        }
        if (target instanceof JsObject object) {
            if (env.resolveHomeClass() instanceof JsClass cls) {
                final var setter = cls.getPrivateInstanceSetter(name);
                if (setter != null) {
                    callFunction(setter, object, List.of(value));
                    return;
                }
            }
            object.setPrivate(name, value);
            return;
        }
        throw new TypeErrorException(
                "Cannot write private member #" + name + " to an object whose class did not declare it");
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

    private List<JsValue> ownKeysOf(JsValue target) {
        return switch (target) {
            case JsProxy proxy -> proxies.ownKeys(proxy);
            case JsObject object -> objectOwnKeys(object);
            case JsArray array -> arrayOwnKeys(array);
            default -> new ArrayList<>();
        };
    }

    private boolean deleteMemberValue(JsValue target, JsValue keyValue) {
        return switch (target) {
            case JsProxy proxy -> proxies.delete(proxy, keyValue);
            case JsObject object -> object.delete(JsCoercion.toStr(keyValue));
            case JsArray array -> deleteArrayElement(array, JsCoercion.toStr(keyValue));
            default -> true;
        };
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
