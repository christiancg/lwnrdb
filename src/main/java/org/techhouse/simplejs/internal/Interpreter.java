package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.builtins.ArrayBuiltins;
import org.techhouse.simplejs.builtins.DbModule;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.builtins.GlobalScope;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.StringBuiltins;
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
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ClassBody;
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
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StaticBlock;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Interpreter {
    private enum LoopAction {
        CONTINUE_LOOP, BREAK_LOOP, PROPAGATE
    }

    @FunctionalInterface
    private interface LeafBinder {
        void bind(JsNode leaf, JsValue value, Environment env);
    }

    private static final Set<String> LOGICAL_ASSIGN = Set.of("&&=", "||=", "??=");
    private static final Set<String> LEXICAL_KINDS = Set.of("let", "const");

    public record ProgramOutcome(JsValue lastValue, boolean hasReturn, JsValue returnValue, JsValue exportDefault,
            Map<String, JsValue> namedExports) {
    }

    private final EventLoop eventLoop = new EventLoop();
    private final ThreadLocal<Coroutine> currentCoroutine = new ThreadLocal<>();
    private final List<Coroutine> coroutines = new ArrayList<>();

    private final HostBindings host;
    private final int maxDepth;
    private long instructionsRemaining;
    private final long deadlineNanos;
    private int depth;

    private Interpreter(HostBindings host) {
        this.host = host;
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

    private void tick() {
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
        GlobalScope.install(env, eventLoop, this::callValue, host.console());
        for (final var statement : program.getBody()) {
            if (statement instanceof ImportDeclaration importDeclaration) {
                bindImport(importDeclaration, env);
            }
        }
        hoist(program.getBody(), env);
        final var result = new ModuleResult();
        final var coroutine = new Coroutine();
        coroutines.add(coroutine);
        try {
            coroutine.startAsync(() -> {
                currentCoroutine.set(coroutine);
                runModuleBody(program, env, result);
                return JsUndefined.getInstance();
            });
            eventLoop.drain();
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

    private void runModuleBody(Program program, Environment env, ModuleResult result) {
        moduleLoop : for (final var statement : program.getBody()) {
            switch (statement) {
                case ImportDeclaration ignored -> {
                    // already bound in the pre-pass above
                }
                case ExportDefaultDeclaration exportDefaultDeclaration ->
                    result.exportDefault = evalExportDefault(exportDefaultDeclaration, env);
                case ExportNamedDeclaration exportNamedDeclaration ->
                    evalExportNamed(exportNamedDeclaration, env, result.namedExports);
                case ExportAllDeclaration exportAllDeclaration ->
                    evalExportAll(exportAllDeclaration, result.namedExports);
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

    private JsValue resolveModule(String source) {
        return switch (source) {
            case "args" -> host.args() == null ? new JsObject() : EJsonInterop.fromEjson(host.args());
            case "db" -> {
                if (host.database() == null) {
                    throw new JsThrowException(ErrorBuiltins.makeError("Error", "Database access is not available"));
                }
                yield DbModule.create(host.database());
            }
            default ->
                throw new JsThrowException(ErrorBuiltins.makeError("Error", "Cannot find module '" + source + "'"));
        };
    }

    private void bindImport(ImportDeclaration declaration, Environment env) {
        final var namespace = resolveModule(declaration.getSource().getValue());
        for (final var specifier : declaration.getSpecifiers()) {
            switch (specifier) {
                case ImportDefaultSpecifier defaultSpecifier ->
                    defineModuleBinding(env, defaultSpecifier.getLocal().getName(), namespace);
                case ImportNamespaceSpecifier namespaceSpecifier ->
                    defineModuleBinding(env, namespaceSpecifier.getLocal().getName(), namespace);
                case ImportSpecifier importSpecifier -> defineModuleBinding(env, importSpecifier.getLocal().getName(),
                        moduleMember(namespace, moduleName(importSpecifier.getImported())));
                default -> throw new UnsupportedNodeException(specifier.getType().name());
            }
        }
    }

    private JsValue evalExportDefault(ExportDefaultDeclaration declaration, Environment env) {
        final var value = declaration.getDeclaration();
        if (value instanceof FunctionDeclaration functionDeclaration) {
            final var name = functionDeclaration.getName() == null ? null : functionDeclaration.getName().getName();
            return makeFunction(name, functionDeclaration.getParams(), functionDeclaration.getBody(), false, false,
                    functionDeclaration.isAsync(), functionDeclaration.isGenerator(), env);
        }
        if (value instanceof ClassDeclaration classDeclaration) {
            evalClassDeclaration(classDeclaration, env);
            return env.get(classDeclaration.getId().getName());
        }
        return eval((Expression) value, env);
    }

    private void evalExportNamed(ExportNamedDeclaration declaration, Environment env, Map<String, JsValue> exports) {
        if (declaration.getDeclaration() instanceof Statement inner) {
            evalStatement(inner, env);
            final var names = new ArrayList<String>();
            collectExportedNames(inner, names);
            for (final var name : names) {
                exports.put(name, env.get(name));
            }
            return;
        }
        for (final var specifier : declaration.getSpecifiers()) {
            final var local = moduleName(specifier.getLocal());
            exports.put(moduleName(specifier.getExported()), env.get(local));
        }
    }

    private void evalExportAll(ExportAllDeclaration declaration, Map<String, JsValue> exports) {
        final var namespace = resolveModule(declaration.getSource().getValue());
        if (declaration.getExported() != null) {
            exports.put(declaration.getExported().getName(), namespace);
        } else if (namespace instanceof JsObject object) {
            for (final var key : object.keys()) {
                exports.put(key, object.get(key));
            }
        }
    }

    private void collectExportedNames(Statement declaration, List<String> names) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration -> {
                for (final var declarator : variableDeclaration.getDeclarations()) {
                    collectBoundNames(declarator.getId(), names);
                }
            }
            case FunctionDeclaration functionDeclaration -> names.add(functionDeclaration.getName().getName());
            case ClassDeclaration classDeclaration -> names.add(classDeclaration.getId().getName());
            default -> {
                // no exported bindings
            }
        }
    }

    private JsValue moduleMember(JsValue namespace, String name) {
        if (namespace instanceof JsObject object) {
            return object.get(name);
        }
        return JsUndefined.getInstance();
    }

    private String moduleName(JsNode node) {
        return switch (node) {
            case Identifier identifier -> identifier.getName();
            case StringLiteral literal -> literal.getValue();
            default -> throw new UnsupportedNodeException(node.getType().name());
        };
    }

    private void defineModuleBinding(Environment env, String name, JsValue value) {
        if (!env.hasLocal(name)) {
            env.declareVar(name);
        }
        env.assign(name, value);
    }

    private void hoist(List<Statement> body, Environment env) {
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

    private Completion evalStatement(Statement statement, Environment env) {
        return switch (statement.getType()) {
            case BLOCK_STATEMENT -> evalBlock((BlockStatement) statement, env);
            case EMPTY_STATEMENT -> Completion.empty();
            case EXPRESSION_STATEMENT ->
                Completion.normal(eval(((ExpressionStatement) statement).getExpression(), env));
            case VARIABLE_DECLARATION -> evalVariableDeclaration((VariableDeclaration) statement, env);
            case IF_STATEMENT -> evalIf((IfStatement) statement, env);
            case WHILE_STATEMENT -> evalWhile((WhileStatement) statement, env, null);
            case DO_WHILE_STATEMENT -> evalDoWhile((DoWhileStatement) statement, env, null);
            case FOR_STATEMENT -> evalFor((ForStatement) statement, env, null);
            case FOR_IN_STATEMENT -> evalForIn((ForInStatement) statement, env, null);
            case FOR_OF_STATEMENT -> evalForOf((ForOfStatement) statement, env, null);
            case LABELED_STATEMENT -> evalLabeled((LabeledStatement) statement, env);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) statement, env, null);
            case BREAK_STATEMENT -> Completion.breakOut(labelName(((BreakStatement) statement).getLabel()));
            case CONTINUE_STATEMENT -> Completion.continueOut(labelName(((ContinueStatement) statement).getLabel()));
            case RETURN_STATEMENT -> evalReturn((ReturnStatement) statement, env);
            case THROW_STATEMENT -> throw new JsThrowException(eval(((ThrowStatement) statement).getArgument(), env));
            case TRY_STATEMENT -> evalTry((TryStatement) statement, env);
            case CLASS_DECLARATION -> evalClassDeclaration((ClassDeclaration) statement, env);
            case FUNCTION_DECLARATION -> Completion.empty();
            case IMPORT_DECLARATION -> {
                bindImport((ImportDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_NAMED_DECLARATION -> {
                final var declaration = ((ExportNamedDeclaration) statement).getDeclaration();
                yield declaration instanceof Statement inner ? evalStatement(inner, env) : Completion.empty();
            }
            case EXPORT_DEFAULT_DECLARATION -> {
                evalExportDefault((ExportDefaultDeclaration) statement, env);
                yield Completion.empty();
            }
            case EXPORT_ALL_DECLARATION -> Completion.empty();
            default -> throw new UnsupportedNodeException(statement.getType().name());
        };
    }

    private Completion evalBlock(BlockStatement block, Environment env) {
        final var blockEnv = env.child();
        hoist(block.getBody(), blockEnv);
        for (final var statement : block.getBody()) {
            final var completion = evalStatement(statement, blockEnv);
            if (!completion.isNormal()) {
                return completion;
            }
        }
        return Completion.empty();
    }

    private Completion evalVariableDeclaration(VariableDeclaration declaration, Environment env) {
        final var kind = declaration.getKind();
        if (!LEXICAL_KINDS.contains(kind) && !"var".equals(kind)) {
            throw new UnsupportedNodeException("VariableDeclaration kind '" + kind + "'");
        }
        for (final var declarator : declaration.getDeclarations()) {
            final var id = declarator.getId();
            final var init = declarator.getInit();
            if (id instanceof Identifier identifier) {
                final var name = identifier.getName();
                final var value = init == null ? JsUndefined.getInstance() : eval(init, env);
                if (LEXICAL_KINDS.contains(kind)) {
                    env.initialize(name, value);
                } else if (init != null) {
                    env.assign(name, value);
                }
            } else {
                final var value = init == null ? JsUndefined.getInstance() : eval(init, env);
                destructure(id, value, env, declarationLeaf(kind));
            }
        }
        return Completion.empty();
    }

    private Completion evalIf(IfStatement statement, Environment env) {
        if (JsCoercion.toBoolean(eval(statement.getTest(), env))) {
            return evalStatement(statement.getConsequent(), env);
        }
        if (statement.getAlternate() != null) {
            return evalStatement(statement.getAlternate(), env);
        }
        return Completion.empty();
    }

    private Completion evalWhile(WhileStatement statement, Environment env, String label) {
        while (JsCoercion.toBoolean(eval(statement.getTest(), env))) {
            tick();
            final var completion = evalStatement(statement.getBody(), env);
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

    private Completion evalDoWhile(DoWhileStatement statement, Environment env, String label) {
        do {
            tick();
            final var completion = evalStatement(statement.getBody(), env);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
        } while (JsCoercion.toBoolean(eval(statement.getTest(), env)));
        return Completion.empty();
    }

    private Completion evalFor(ForStatement statement, Environment env, String label) {
        final var loopEnv = env.child();
        final var init = statement.getInit();
        if (init instanceof VariableDeclaration declaration) {
            hoist(List.of(declaration), loopEnv);
            evalVariableDeclaration(declaration, loopEnv);
        } else if (init instanceof Expression expression) {
            eval(expression, loopEnv);
        }
        while (statement.getTest() == null || JsCoercion.toBoolean(eval(statement.getTest(), loopEnv))) {
            tick();
            final var completion = evalStatement(statement.getBody(), loopEnv);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                break;
            }
            if (statement.getUpdate() != null) {
                eval(statement.getUpdate(), loopEnv);
            }
        }
        return Completion.empty();
    }

    private Completion evalForOf(ForOfStatement statement, Environment env, String label) {
        if (statement.isAwait()) {
            return evalForAwaitOf(statement, env, label);
        }
        final var iteration = new Iteration(eval(statement.getRight(), env));
        var value = iteration.next();
        while (value != null) {
            tick();
            final var iterationEnv = env.child();
            bindForTarget(statement.getLeft(), value, iterationEnv);
            final var completion = evalStatement(statement.getBody(), iterationEnv);
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

    private Completion evalForAwaitOf(ForOfStatement statement, Environment env, String label) {
        final var coroutine = currentCoroutine.get();
        if (coroutine == null) {
            throw new SyntaxErrorException("for await is only valid inside an async function");
        }
        final var source = eval(statement.getRight(), env);
        if (source instanceof JsAsyncGenerator generator) {
            return iterateAsyncGenerator(statement, env, label, coroutine, generator);
        }
        return iterateAsyncValues(statement, env, label, coroutine, new Iteration(source));
    }

    private Completion iterateAsyncGenerator(ForOfStatement statement, Environment env, String label,
            Coroutine coroutine, JsAsyncGenerator generator) {
        while (true) {
            tick();
            final var step = coroutine
                    .await(toPromise(driveAsyncGenerator(generator, AsyncStep.NEXT, JsUndefined.getInstance())));
            if (JsCoercion.toBoolean(getMember(step, "done"))) {
                break;
            }
            final var iterationEnv = env.child();
            bindForTarget(statement.getLeft(), getMember(step, "value"), iterationEnv);
            final var completion = evalStatement(statement.getBody(), iterationEnv);
            final var action = classify(completion, label);
            if (action == LoopAction.PROPAGATE) {
                driveAsyncGenerator(generator, AsyncStep.RETURN, JsUndefined.getInstance());
                return completion;
            }
            if (action == LoopAction.BREAK_LOOP) {
                driveAsyncGenerator(generator, AsyncStep.RETURN, JsUndefined.getInstance());
                break;
            }
        }
        return Completion.empty();
    }

    private Completion iterateAsyncValues(ForOfStatement statement, Environment env, String label, Coroutine coroutine,
            Iteration iteration) {
        var value = iteration.next();
        while (value != null) {
            tick();
            final var awaited = coroutine.await(toPromise(value));
            final var iterationEnv = env.child();
            bindForTarget(statement.getLeft(), awaited, iterationEnv);
            final var completion = evalStatement(statement.getBody(), iterationEnv);
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

    private Completion evalForIn(ForInStatement statement, Environment env, String label) {
        final var target = eval(statement.getRight(), env);
        for (final var key : enumerateKeys(target)) {
            tick();
            final var iterationEnv = env.child();
            bindForTarget(statement.getLeft(), new JsString(key), iterationEnv);
            final var completion = evalStatement(statement.getBody(), iterationEnv);
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

    private void bindForTarget(JsNode left, JsValue value, Environment env) {
        if (left instanceof VariableDeclaration declaration) {
            final var kind = declaration.getKind();
            final var id = declaration.getDeclarations().getFirst().getId();
            final var names = new ArrayList<String>();
            collectBoundNames(id, names);
            for (final var name : names) {
                if (LEXICAL_KINDS.contains(kind)) {
                    env.declareLexical(name, kind);
                } else {
                    env.declareVar(name);
                }
            }
            destructure(id, value, env, declarationLeaf(kind));
        } else {
            destructure(left, value, env, assignmentLeaf());
        }
    }

    private List<String> enumerateKeys(JsValue target) {
        if (target instanceof JsObject object) {
            return new ArrayList<>(object.keys());
        }
        if (target instanceof JsArray array) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < array.length(); i++) {
                keys.add(Integer.toString(i));
            }
            return keys;
        }
        if (target instanceof JsString string) {
            final var keys = new ArrayList<String>();
            for (var i = 0; i < string.getValue().length(); i++) {
                keys.add(Integer.toString(i));
            }
            return keys;
        }
        return List.of();
    }

    private Completion evalLabeled(LabeledStatement statement, Environment env) {
        final var label = statement.getLabel().getName();
        final var body = statement.getBody();
        final var completion = switch (body.getType()) {
            case WHILE_STATEMENT -> evalWhile((WhileStatement) body, env, label);
            case DO_WHILE_STATEMENT -> evalDoWhile((DoWhileStatement) body, env, label);
            case FOR_STATEMENT -> evalFor((ForStatement) body, env, label);
            case FOR_IN_STATEMENT -> evalForIn((ForInStatement) body, env, label);
            case FOR_OF_STATEMENT -> evalForOf((ForOfStatement) body, env, label);
            case SWITCH_STATEMENT -> evalSwitch((SwitchStatement) body, env, label);
            default -> evalStatement(body, env);
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

    private boolean matchesLabel(String completionLabel, String loopLabel) {
        return completionLabel == null || completionLabel.equals(loopLabel);
    }

    private String labelName(Identifier label) {
        return label == null ? null : label.getName();
    }

    private Completion evalReturn(ReturnStatement statement, Environment env) {
        final var argument = statement.getArgument();
        return Completion.returnValue(argument == null ? JsUndefined.getInstance() : eval(argument, env));
    }

    private Completion evalTry(TryStatement statement, Environment env) {
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
                result = evalCatch(statement.getHandler(), toErrorValue(error), env);
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

    private Completion evalCatch(CatchClause handler, JsValue error, Environment env) {
        final var catchEnv = env.child();
        final var param = handler.getParam();
        if (param instanceof Identifier id) {
            catchEnv.declareLexical(id.getName(), "let");
            catchEnv.initialize(id.getName(), error);
        } else if (param != null) {
            final var names = new ArrayList<String>();
            collectBoundNames(param, names);
            for (final var name : names) {
                catchEnv.declareLexical(name, "let");
            }
            destructure(param, error, catchEnv, declarationLeaf("let"));
        }
        return evalBlock(handler.getBody(), catchEnv);
    }

    private JsValue toErrorValue(RuntimeException error) {
        if (error instanceof JsThrowException thrown) {
            return thrown.getValue();
        }
        final var name = switch (error) {
            case TypeErrorException ignored -> "TypeError";
            case ReferenceErrorException ignored -> "ReferenceError";
            case RangeErrorException ignored -> "RangeError";
            default -> "SyntaxError";
        };
        return ErrorBuiltins.makeError(name, error.getMessage());
    }

    private Completion evalSwitch(SwitchStatement statement, Environment env, String label) {
        final var switchEnv = env.child();
        for (final var switchCase : statement.getCases()) {
            hoist(switchCase.getConsequent(), switchEnv);
        }
        final var discriminant = eval(statement.getDiscriminant(), switchEnv);
        final var cases = statement.getCases();
        var start = -1;
        var defaultIndex = -1;
        for (var i = 0; i < cases.size(); i++) {
            final var test = cases.get(i).getTest();
            if (test == null) {
                defaultIndex = i;
            } else if (JsOperators.strictEquals(discriminant, eval(test, switchEnv))) {
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
        for (var i = start; i < cases.size(); i++) {
            for (final var consequent : cases.get(i).getConsequent()) {
                final var completion = evalStatement(consequent, switchEnv);
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

    private JsValue eval(Expression expression, Environment env) {
        return switch (expression.getType()) {
            case NUMBER_LITERAL -> new JsNumber(((NumberLiteral) expression).getValue().doubleValue());
            case BIGINT_LITERAL -> new JsBigInt(((BigIntLiteral) expression).getValue());
            case STRING_LITERAL -> new JsString(((StringLiteral) expression).getValue());
            case BOOLEAN_LITERAL -> JsBoolean.of(((BooleanLiteral) expression).getValue());
            case NULL_LITERAL -> JsNull.getInstance();
            case UNDEFINED_LITERAL -> JsUndefined.getInstance();
            case REGEX_LITERAL -> RegexTranslator.compile(((RegexLiteral) expression).getPattern(),
                    ((RegexLiteral) expression).getFlags());
            case TEMPLATE_LITERAL -> evalTemplate((TemplateLiteral) expression, env);
            case IDENTIFIER -> env.get(((Identifier) expression).getName());
            case THIS_EXPRESSION -> env.resolveThis();
            case FUNCTION_EXPRESSION -> evalFunctionExpression((FunctionExpression) expression, env);
            case ARROW_FUNCTION_EXPRESSION -> evalArrowFunction((ArrowFunctionExpression) expression, env);
            case CALL_EXPRESSION -> evalCall((CallExpression) expression, env);
            case NEW_EXPRESSION -> evalNew((NewExpression) expression, env);
            case ARRAY_EXPRESSION -> evalArray((ArrayExpression) expression, env);
            case OBJECT_EXPRESSION -> evalObject((ObjectExpression) expression, env);
            case UNARY_EXPRESSION -> evalUnary((UnaryExpression) expression, env);
            case UPDATE_EXPRESSION -> evalUpdate((UpdateExpression) expression, env);
            case BINARY_EXPRESSION -> evalBinary((BinaryExpression) expression, env);
            case LOGICAL_EXPRESSION -> evalLogical((LogicalExpression) expression, env);
            case ASSIGNMENT_EXPRESSION -> evalAssignment((AssignmentExpression) expression, env);
            case CONDITIONAL_EXPRESSION -> evalConditional((ConditionalExpression) expression, env);
            case MEMBER_EXPRESSION -> evalMember((MemberExpression) expression, env);
            case CLASS_EXPRESSION -> evalClassExpression((ClassExpression) expression, env);
            case YIELD_EXPRESSION -> evalYield((YieldExpression) expression, env);
            case AWAIT_EXPRESSION -> evalAwait((AwaitExpression) expression, env);
            case SUPER_EXPRESSION -> throw new SyntaxErrorException("'super' keyword unexpected here");
            default -> throw new UnsupportedNodeException(expression.getType().name());
        };
    }

    private JsValue evalTemplate(TemplateLiteral template, Environment env) {
        final var quasis = template.getQuasis();
        final var expressions = template.getExpressions();
        final var sb = new StringBuilder(quasis.getFirst());
        for (var i = 0; i < expressions.size(); i++) {
            sb.append(JsCoercion.toStr(eval(expressions.get(i), env)));
            sb.append(quasis.get(i + 1));
        }
        return new JsString(sb.toString());
    }

    private JsValue evalArray(ArrayExpression array, Environment env) {
        final var result = new JsArray();
        for (final var element : array.getElements()) {
            if (element == null) {
                result.push(JsUndefined.getInstance());
            } else if (element instanceof SpreadElement spread) {
                spreadInto(result.getElements(), eval(spread.getArgument(), env));
            } else {
                result.push(eval(element, env));
            }
        }
        return result;
    }

    private void spreadInto(List<JsValue> target, JsValue value) {
        switch (value) {
            case JsArray array -> target.addAll(array.getElements());
            case JsString string -> {
                for (var i = 0; i < string.getValue().length(); i++) {
                    target.add(new JsString(String.valueOf(string.getValue().charAt(i))));
                }
            }
            case JsGenerator ignored -> {
                final var iteration = new Iteration(value);
                var element = iteration.next();
                while (element != null) {
                    target.add(element);
                    element = iteration.next();
                }
            }
            default -> throw new TypeErrorException(JsCoercion.toStr(value) + " is not iterable");
        }
    }

    private void spreadObject(JsObject target, JsValue source) {
        switch (source) {
            case JsObject object -> {
                for (final var entry : object.getProperties().entrySet()) {
                    target.set(entry.getKey(), entry.getValue());
                }
            }
            case JsArray array -> {
                final var elements = array.getElements();
                for (var i = 0; i < elements.size(); i++) {
                    target.set(Integer.toString(i), elements.get(i));
                }
            }
            case JsString string -> {
                for (var i = 0; i < string.getValue().length(); i++) {
                    target.set(Integer.toString(i), new JsString(String.valueOf(string.getValue().charAt(i))));
                }
            }
            default -> {
            }
        }
    }

    private JsValue evalObject(ObjectExpression object, Environment env) {
        final var result = new JsObject();
        for (final var member : object.getProperties()) {
            if (member instanceof SpreadElement spread) {
                spreadObject(result, eval(spread.getArgument(), env));
                continue;
            }
            if (!(member instanceof Property property)) {
                throw new UnsupportedNodeException(member.getType().name());
            }
            final var key = property.isComputed()
                    ? JsCoercion.toStr(eval(property.getKey(), env))
                    : staticKeyName(property.getKey());
            if (!(property.getValue() instanceof Expression value)) {
                throw new UnsupportedNodeException(property.getValue().getType().name());
            }
            result.set(key, eval(value, env));
        }
        return result;
    }

    private String staticKeyName(Expression key) {
        return switch (key.getType()) {
            case IDENTIFIER -> ((Identifier) key).getName();
            case STRING_LITERAL -> ((StringLiteral) key).getValue();
            case NUMBER_LITERAL -> JsCoercion.toStr(new JsNumber(((NumberLiteral) key).getValue().doubleValue()));
            default -> throw new UnsupportedNodeException(key.getType().name());
        };
    }

    private JsValue evalUnary(UnaryExpression unary, Environment env) {
        final var operator = unary.getOperator();
        if ("typeof".equals(operator)) {
            return evalTypeof(unary.getArgument(), env);
        }
        if ("delete".equals(operator)) {
            return evalDelete(unary.getArgument(), env);
        }
        return JsOperators.unary(operator, eval(unary.getArgument(), env));
    }

    private JsValue evalTypeof(Expression argument, Environment env) {
        if (argument instanceof Identifier id) {
            try {
                return new JsString(JsCoercion.typeOf(env.get(id.getName())));
            } catch (ReferenceErrorException ignored) {
                return new JsString("undefined");
            }
        }
        return new JsString(JsCoercion.typeOf(eval(argument, env)));
    }

    private JsValue evalDelete(Expression argument, Environment env) {
        if (argument instanceof MemberExpression member) {
            final var target = eval(member.getObject(), env);
            final var key = memberKey(member, env);
            if (target instanceof JsObject object) {
                object.delete(key);
            } else if (target instanceof JsArray array) {
                final var index = arrayIndex(key);
                if (index != null && index < array.length()) {
                    array.set(index, JsUndefined.getInstance());
                }
            }
        }
        return JsBoolean.TRUE;
    }

    private JsValue evalUpdate(UpdateExpression update, Environment env) {
        final var increment = "++".equals(update.getOperator());
        final var argument = update.getArgument();
        if (argument instanceof Identifier id) {
            final var oldValue = env.get(id.getName());
            final var newValue = JsOperators.delta(oldValue, increment);
            env.assign(id.getName(), newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue);
        }
        if (argument instanceof MemberExpression member) {
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                final var object = eval(member.getObject(), env);
                final var oldValue = getPrivateMember(object, priv.getName(), env);
                final var newValue = JsOperators.delta(oldValue, increment);
                setPrivateMember(object, priv.getName(), newValue, env);
                return update.isPrefix() ? newValue : numericOld(oldValue);
            }
            final var target = eval(member.getObject(), env);
            final var key = memberKey(member, env);
            final var oldValue = getMember(target, key);
            final var newValue = JsOperators.delta(oldValue, increment);
            setMember(target, key, newValue);
            return update.isPrefix() ? newValue : numericOld(oldValue);
        }
        throw new UnsupportedNodeException(argument.getType().name());
    }

    private JsValue numericOld(JsValue oldValue) {
        if (oldValue instanceof JsBigInt) {
            return oldValue;
        }
        return new JsNumber(JsCoercion.toNumber(oldValue));
    }

    private JsValue evalBinary(BinaryExpression binary, Environment env) {
        final var operator = binary.getOperator();
        if ("instanceof".equals(operator)) {
            return evalInstanceof(eval(binary.getLeft(), env), eval(binary.getRight(), env));
        }
        if ("in".equals(operator)) {
            if (binary.getLeft() instanceof PrivateIdentifier priv) {
                return evalBrandCheck(priv, eval(binary.getRight(), env), env);
            }
            return evalIn(binary, env);
        }
        return JsOperators.binary(operator, eval(binary.getLeft(), env), eval(binary.getRight(), env));
    }

    private JsValue evalIn(BinaryExpression binary, Environment env) {
        final var key = JsCoercion.toStr(eval(binary.getLeft(), env));
        final var container = eval(binary.getRight(), env);
        if (container instanceof JsObject object) {
            return JsBoolean.of(object.has(key));
        }
        if (container instanceof JsArray array) {
            if ("length".equals(key)) {
                return JsBoolean.TRUE;
            }
            final var index = arrayIndex(key);
            return JsBoolean.of(index != null && index < array.length());
        }
        throw new TypeErrorException("Cannot use 'in' operator to search for '" + key + "'");
    }

    private JsValue evalLogical(LogicalExpression logical, Environment env) {
        final var left = eval(logical.getLeft(), env);
        return switch (logical.getOperator()) {
            case "&&" -> JsCoercion.toBoolean(left) ? eval(logical.getRight(), env) : left;
            case "||" -> JsCoercion.toBoolean(left) ? left : eval(logical.getRight(), env);
            case "??" -> isNullish(left) ? eval(logical.getRight(), env) : left;
            default -> throw new TypeErrorException("Unknown logical operator: " + logical.getOperator());
        };
    }

    private JsValue evalConditional(ConditionalExpression conditional, Environment env) {
        if (JsCoercion.toBoolean(eval(conditional.getTest(), env))) {
            return eval(conditional.getConsequent(), env);
        }
        return eval(conditional.getAlternate(), env);
    }

    private JsValue evalAssignment(AssignmentExpression assignment, Environment env) {
        final var target = assignment.getTarget();
        if (target instanceof Identifier id) {
            return assignToIdentifier(id.getName(), assignment, env);
        }
        if (target instanceof MemberExpression member) {
            return assignToMember(member, assignment, env);
        }
        if (target instanceof ArrayPattern || target instanceof ObjectPattern) {
            final var value = eval(assignment.getValue(), env);
            destructure(target, value, env, assignmentLeaf());
            return value;
        }
        throw new UnsupportedNodeException(target.getType().name());
    }

    private JsValue assignToIdentifier(String name, AssignmentExpression assignment, Environment env) {
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = eval(assignment.getValue(), env);
            env.assign(name, value);
            return value;
        }
        final var current = env.get(name);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = eval(assignment.getValue(), env);
            env.assign(name, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env));
        env.assign(name, value);
        return value;
    }

    private JsValue assignToMember(MemberExpression member, AssignmentExpression assignment, Environment env) {
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            return assignToPrivate(member, priv, assignment, env);
        }
        final var target = eval(member.getObject(), env);
        final var key = memberKey(member, env);
        final var operator = assignment.getOperator();
        if ("=".equals(operator)) {
            final var value = eval(assignment.getValue(), env);
            setMember(target, key, value);
            return value;
        }
        final var current = getMember(target, key);
        if (LOGICAL_ASSIGN.contains(operator)) {
            if (shouldNotApplyLogical(operator, current)) {
                return current;
            }
            final var value = eval(assignment.getValue(), env);
            setMember(target, key, value);
            return value;
        }
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env));
        setMember(target, key, value);
        return value;
    }

    private boolean shouldNotApplyLogical(String operator, JsValue current) {
        return !switch (operator) {
            case "&&=" -> JsCoercion.toBoolean(current);
            case "||=" -> !JsCoercion.toBoolean(current);
            case "??=" -> isNullish(current);
            default -> throw new TypeErrorException("Unknown logical assignment: " + operator);
        };
    }

    private String baseOperator(String assignmentOperator) {
        return assignmentOperator.substring(0, assignmentOperator.length() - 1);
    }

    private JsValue evalMember(MemberExpression member, Environment env) {
        if (member.getObject() instanceof SuperExpression) {
            return evalSuperMemberRead(member, env);
        }
        if (member.getProperty() instanceof PrivateIdentifier priv) {
            final var privateTarget = eval(member.getObject(), env);
            if (member.isOptional() && isNullish(privateTarget)) {
                return JsUndefined.getInstance();
            }
            return getPrivateMember(privateTarget, priv.getName(), env);
        }
        final var target = eval(member.getObject(), env);
        if (member.isOptional() && isNullish(target)) {
            return JsUndefined.getInstance();
        }
        final var key = memberKey(member, env);
        return getMember(target, key);
    }

    private String memberKey(MemberExpression member, Environment env) {
        if (member.isComputed()) {
            return JsCoercion.toStr(eval(member.getProperty(), env));
        }
        if (member.getProperty() instanceof Identifier id) {
            return id.getName();
        }
        throw new UnsupportedNodeException(member.getProperty().getType().name());
    }

    private JsValue getMember(JsValue target, String key) {
        return switch (target) {
            case JsObject object -> getObjectMember(object, key);
            case JsClass cls -> getStaticMember(cls, key);
            case JsArray array -> getArrayMember(array, key);
            case JsString string -> getStringMember(string, key);
            case JsGenerator generator -> generatorMethod(generator, key);
            case JsAsyncGenerator generator -> asyncGeneratorMethod(generator, key);
            case JsRegExp regexp -> regExpMember(regexp, key);
            case JsPromise promise -> promiseMethod(promise, key);
            case JsNativeFunction fn when fn.hasProperty(key) -> fn.getProperty(key);
            case JsNull ignored -> throw cannotReadProperties(target, key);
            case JsUndefined ignored -> throw cannotReadProperties(target, key);
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue getObjectMember(JsObject object, String key) {
        final var cls = object.getKlass();
        if (cls != null && !object.has(key)) {
            final var getter = cls.findInstanceGetter(key);
            if (getter != null) {
                return callFunction(getter, object, List.of());
            }
            final var method = cls.findInstanceMethod(key);
            if (method != null) {
                return method;
            }
        }
        return object.get(key);
    }

    private JsValue getArrayMember(JsArray array, String key) {
        if ("length".equals(key)) {
            return new JsNumber(array.length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return array.get(index);
        }
        final var method = ArrayBuiltins.getMethod(array, key, this::callValue);
        return method == null ? JsUndefined.getInstance() : method;
    }

    private JsValue getStringMember(JsString string, String key) {
        if ("length".equals(key)) {
            return new JsNumber(string.getValue().length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return index < string.getValue().length()
                    ? new JsString(String.valueOf(string.getValue().charAt(index)))
                    : JsUndefined.getInstance();
        }
        final var method = StringBuiltins.getMethod(string, key, this::callValue);
        return method == null ? JsUndefined.getInstance() : method;
    }

    private TypeErrorException cannotReadProperties(JsValue target, String key) {
        return new TypeErrorException(
                "Cannot read properties of " + JsCoercion.toStr(target) + " (reading '" + key + "')");
    }

    private void setMember(JsValue target, String key, JsValue value) {
        switch (target) {
            case JsObject object -> {
                final var cls = object.getKlass();
                if (cls != null && !object.has(key)) {
                    final var setter = cls.findInstanceSetter(key);
                    if (setter != null) {
                        callFunction(setter, object, List.of(value));
                        return;
                    }
                }
                object.set(key, value);
            }
            case JsClass cls -> {
                final var setter = cls.findStaticSetter(key);
                if (setter != null) {
                    callFunction(setter, cls, List.of(value));
                } else {
                    cls.setStaticProp(key, value);
                }
            }
            case JsArray array -> {
                final var index = arrayIndex(key);
                if (index != null) {
                    array.set(index, value);
                }
            }
            case JsRegExp regexp -> {
                if ("lastIndex".equals(key)) {
                    final var next = JsCoercion.toNumber(value);
                    regexp.setLastIndex(Double.isNaN(next) ? 0 : (int) next);
                }
            }
            case JsNull ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsUndefined ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            default -> {
            }
        }
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

    private JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow,
            boolean expressionBody, boolean async, boolean generator, Environment closure) {
        return new JsFunction(name, params, body, arrow, expressionBody, async, generator, closure);
    }

    private JsValue evalCall(CallExpression call, Environment env) {
        final var callee = call.getCallee();
        if (callee instanceof SuperExpression) {
            return evalSuperCall(call, env);
        }
        var thisArg = (JsValue) JsUndefined.getInstance();
        final JsValue function;
        if (callee instanceof MemberExpression member) {
            if (member.getObject() instanceof SuperExpression) {
                return evalSuperMemberCall(member, call, env);
            }
            final var object = eval(member.getObject(), env);
            if (member.isOptional() && isNullish(object)) {
                return JsUndefined.getInstance();
            }
            thisArg = object;
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                function = getPrivateMember(object, priv.getName(), env);
            } else {
                function = getMember(object, memberKey(member, env));
            }
        } else {
            function = eval(callee, env);
        }
        return callValue(function, thisArg, evalArguments(call.getArguments(), env));
    }

    private JsValue evalNew(NewExpression expression, Environment env) {
        final var callee = eval(expression.getCallee(), env);
        final var args = evalArguments(expression.getArguments(), env);
        if (callee instanceof JsClass cls) {
            return construct(cls, args);
        }
        if (callee instanceof JsNativeFunction nativeFunction) {
            return nativeFunction.invoke(JsUndefined.getInstance(), args);
        }
        if (callee instanceof JsFunction function && !function.isArrow()) {
            final var instance = new JsObject();
            final var result = callFunction(function, instance, args);
            return isObjectLike(result) ? result : instance;
        }
        throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a constructor");
    }

    private boolean isObjectLike(JsValue value) {
        return value instanceof JsObject || value instanceof JsArray || value instanceof JsFunction
                || value instanceof JsNativeFunction || value instanceof JsClass;
    }

    private List<JsValue> evalArguments(List<Expression> arguments, Environment env) {
        final var values = new ArrayList<JsValue>();
        for (final var argument : arguments) {
            if (argument instanceof SpreadElement spread) {
                spreadInto(values, eval(spread.getArgument(), env));
            } else {
                values.add(eval(argument, env));
            }
        }
        return values;
    }

    private JsValue callValue(JsValue callee, JsValue thisArg, List<JsValue> args) {
        tick();
        if (callee instanceof JsFunction function) {
            return callFunction(function, thisArg, args);
        }
        if (callee instanceof JsNativeFunction nativeFunction) {
            return nativeFunction.invoke(thisArg, args);
        }
        throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a function");
    }

    private JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args) {
        if (maxDepth >= 0 && depth >= maxDepth) {
            throw new ScriptLimitException("Script exceeded its maximum call depth");
        }
        depth++;
        try {
            final var activation = function.getClosure().functionChild();
            if (!function.isArrow()) {
                activation.defineThis(thisArg);
            }
            bindParams(function.getParams(), args, activation);
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
        for (final var statement : body.getBody()) {
            final var completion = evalStatement(statement, activation);
            if (completion.kind() == Completion.Kind.RETURN) {
                return completion.value();
            }
            if (!completion.isNormal()) {
                break;
            }
        }
        return JsUndefined.getInstance();
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
        coroutine.startAsync(() -> {
            currentCoroutine.set(coroutine);
            try {
                promise.resolve(runFunctionBody(function, activation));
            } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                    | SyntaxErrorException error) {
                promise.reject(toErrorValue(error));
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
        coroutine.setResumeObserver(escaped -> observeAsyncGenerator(generator, escaped));
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
        final var iteration = new Iteration(iterable);
        var value = iteration.next();
        while (value != null) {
            coroutine.yieldOut(value);
            value = iteration.next();
        }
        return JsUndefined.getInstance();
    }

    private JsValue yieldDelegateAsync(Coroutine coroutine, JsAsyncGenerator generator) {
        while (true) {
            final var step = coroutine
                    .await(toPromise(driveAsyncGenerator(generator, AsyncStep.NEXT, JsUndefined.getInstance())));
            if (JsCoercion.toBoolean(getMember(step, "done"))) {
                return getMember(step, "value");
            }
            coroutine.yieldOut(getMember(step, "value"));
        }
    }

    private JsValue evalAwait(AwaitExpression await, Environment env) {
        final var coroutine = currentCoroutine.get();
        if (coroutine == null) {
            throw new SyntaxErrorException("await is only valid inside an async function");
        }
        return coroutine.await(toPromise(eval(await.getArgument(), env)));
    }

    private JsPromise toPromise(JsValue value) {
        if (value instanceof JsPromise promise) {
            return promise;
        }
        final var promise = new JsPromise(eventLoop);
        promise.resolve(value);
        return promise;
    }

    private void bindParams(List<JsNode> params, List<JsValue> args, Environment activation) {
        for (var i = 0; i < params.size(); i++) {
            final var param = params.get(i);
            if (param instanceof RestElement rest) {
                final var restArray = new JsArray();
                for (var j = i; j < args.size(); j++) {
                    restArray.push(args.get(j));
                }
                declareParamNames(rest.getArgument(), activation);
                destructure(rest.getArgument(), restArray, activation, paramLeaf());
                return;
            }
            final var value = i < args.size() ? args.get(i) : JsUndefined.getInstance();
            if (param instanceof Identifier id) {
                activation.declareVar(id.getName());
                activation.assign(id.getName(), value);
            } else {
                declareParamNames(param, activation);
                destructure(param, value, activation, paramLeaf());
            }
        }
    }

    private void declareParamNames(JsNode param, Environment activation) {
        final var names = new ArrayList<String>();
        collectBoundNames(param, names);
        for (final var name : names) {
            activation.declareVar(name);
        }
    }

    private boolean isNullish(JsValue value) {
        return value instanceof JsNull || value instanceof JsUndefined;
    }

    private Integer arrayIndex(String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (var i = 0; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return null;
            }
        }
        if (key.length() > 1 && key.charAt(0) == '0') {
            return null;
        }
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void destructure(JsNode target, JsValue value, Environment env, LeafBinder leaf) {
        switch (target) {
            case AssignmentPattern pattern -> {
                final var resolved = value instanceof JsUndefined ? eval(pattern.getRight(), env) : value;
                destructure(pattern.getLeft(), resolved, env, leaf);
            }
            case ArrayPattern pattern -> destructureArray(pattern, value, env, leaf);
            case ObjectPattern pattern -> destructureObject(pattern, value, env, leaf);
            default -> leaf.bind(target, value, env);
        }
    }

    private void destructureArray(ArrayPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        final var elements = arrayLikeElements(value);
        final var patternElements = pattern.getElements();
        for (var i = 0; i < patternElements.size(); i++) {
            final var element = patternElements.get(i);
            if (element == null) {
                continue;
            }
            if (element instanceof RestElement rest) {
                final var restArray = new JsArray();
                for (var j = i; j < elements.size(); j++) {
                    restArray.push(elements.get(j));
                }
                destructure(rest.getArgument(), restArray, env, leaf);
                return;
            }
            final var elementValue = i < elements.size() ? elements.get(i) : JsUndefined.getInstance();
            destructure(element, elementValue, env, leaf);
        }
    }

    private void destructureObject(ObjectPattern pattern, JsValue value, Environment env, LeafBinder leaf) {
        if (isNullish(value)) {
            throw new TypeErrorException(
                    "Cannot destructure '" + JsCoercion.toStr(value) + "' as it is " + JsCoercion.toStr(value) + ".");
        }
        final var taken = new HashSet<String>();
        for (final var member : pattern.getProperties()) {
            if (member instanceof RestElement rest) {
                final var restObject = new JsObject();
                if (value instanceof JsObject object) {
                    for (final var entry : object.getProperties().entrySet()) {
                        if (!taken.contains(entry.getKey())) {
                            restObject.set(entry.getKey(), entry.getValue());
                        }
                    }
                }
                destructure(rest.getArgument(), restObject, env, leaf);
                return;
            }
            final var property = (Property) member;
            final var key = property.isComputed()
                    ? JsCoercion.toStr(eval(property.getKey(), env))
                    : staticKeyName(property.getKey());
            taken.add(key);
            destructure(property.getValue(), getMember(value, key), env, leaf);
        }
    }

    private List<JsValue> arrayLikeElements(JsValue value) {
        if (value instanceof JsArray array) {
            return array.getElements();
        }
        if (value instanceof JsString string) {
            final var chars = new ArrayList<JsValue>();
            for (var i = 0; i < string.getValue().length(); i++) {
                chars.add(new JsString(String.valueOf(string.getValue().charAt(i))));
            }
            return chars;
        }
        throw new TypeErrorException(JsCoercion.toStr(value) + " is not iterable");
    }

    private void collectBoundNames(JsNode target, List<String> names) {
        switch (target) {
            case Identifier id -> names.add(id.getName());
            case AssignmentPattern pattern -> collectBoundNames(pattern.getLeft(), names);
            case RestElement rest -> collectBoundNames(rest.getArgument(), names);
            case ArrayPattern pattern -> {
                for (final var element : pattern.getElements()) {
                    if (element != null) {
                        collectBoundNames(element, names);
                    }
                }
            }
            case ObjectPattern pattern -> {
                for (final var member : pattern.getProperties()) {
                    if (member instanceof RestElement rest) {
                        collectBoundNames(rest.getArgument(), names);
                    } else {
                        collectBoundNames(((Property) member).getValue(), names);
                    }
                }
            }
            default -> {
            }
        }
    }

    private LeafBinder declarationLeaf(String kind) {
        if (LEXICAL_KINDS.contains(kind)) {
            return (leaf, value, env) -> env.initialize(((Identifier) leaf).getName(), value);
        }
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder paramLeaf() {
        return (leaf, value, env) -> env.assign(((Identifier) leaf).getName(), value);
    }

    private LeafBinder assignmentLeaf() {
        return (leaf, value, env) -> {
            if (leaf instanceof Identifier id) {
                env.assign(id.getName(), value);
            } else if (leaf instanceof MemberExpression member) {
                setMember(eval(member.getObject(), env), memberKey(member, env), value);
            } else {
                throw new UnsupportedNodeException(leaf.getType().name());
            }
        };
    }

    private Completion evalClassDeclaration(ClassDeclaration declaration, Environment env) {
        final var cls = buildClass(declaration.getId(), declaration.getSuperClass(), declaration.getBody(), env);
        final var name = declaration.getId().getName();
        env.declareLexical(name, "let");
        env.initialize(name, cls);
        return Completion.empty();
    }

    private JsValue evalClassExpression(ClassExpression expression, Environment env) {
        return buildClass(expression.getId(), expression.getSuperClass(), expression.getBody(), env);
    }

    private JsClass buildClass(Identifier id, Expression superClassExpr, ClassBody body, Environment env) {
        JsClass superClass = null;
        if (superClassExpr != null) {
            final var resolved = eval(superClassExpr, env);
            if (!(resolved instanceof JsClass sc)) {
                throw new TypeErrorException(
                        "Class extends value " + JsCoercion.toStr(resolved) + " is not a constructor or null");
            }
            superClass = sc;
        }
        final var classScope = env.child();
        final var methodScope = classScope.child();
        final var name = id == null ? null : id.getName();
        final var cls = new JsClass(name, superClass, methodScope);
        methodScope.defineHomeClass(cls);
        if (name != null) {
            classScope.declareLexical(name, "const");
            classScope.initialize(name, cls);
        }
        final var staticInit = new ArrayList<JsNode>();
        for (final var member : body.getMembers()) {
            switch (member) {
                case MethodDefinition method -> installMethod(cls, method, classScope);
                case FieldDefinition field -> {
                    if (field.isStatic()) {
                        staticInit.add(field);
                    } else {
                        cls.addInstanceField(field);
                    }
                }
                case StaticBlock block -> staticInit.add(block);
                default -> throw new UnsupportedNodeException(member.getType().name());
            }
        }
        runStaticInit(cls, staticInit);
        return cls;
    }

    private void installMethod(JsClass cls, MethodDefinition method, Environment classScope) {
        final var value = method.getValue();
        final var fn = makeFunction(null, value.getParams(), value.getBody(), false, false, value.isAsync(),
                value.isGenerator(), cls.getMethodScope());
        final var kind = method.getKind();
        if ("constructor".equals(kind)) {
            cls.setConstructor(fn);
            return;
        }
        if (method.getKey() instanceof PrivateIdentifier priv) {
            cls.addPrivateInstanceMethod(priv.getName(), kind, fn);
            return;
        }
        final var key = method.isComputed()
                ? JsCoercion.toStr(eval(method.getKey(), classScope))
                : staticKeyName(method.getKey());
        if (method.isStatic()) {
            cls.addStaticMethod(key, kind, fn);
        } else {
            cls.addInstanceMethod(key, kind, fn);
        }
    }

    private void runStaticInit(JsClass cls, List<JsNode> staticInit) {
        final var staticScope = cls.getMethodScope().child();
        staticScope.defineThis(cls);
        for (final var node : staticInit) {
            if (node instanceof FieldDefinition field) {
                final var key = field.isComputed()
                        ? JsCoercion.toStr(eval(field.getKey(), staticScope))
                        : staticKeyName(field.getKey());
                final var value = field.getValue() == null
                        ? JsUndefined.getInstance()
                        : eval(field.getValue(), staticScope);
                cls.setStaticProp(key, value);
            } else {
                final var block = (StaticBlock) node;
                final var blockEnv = staticScope.child();
                hoist(block.getBody(), blockEnv);
                for (final var statement : block.getBody()) {
                    if (!evalStatement(statement, blockEnv).isNormal()) {
                        break;
                    }
                }
            }
        }
    }

    private JsValue construct(JsClass cls, List<JsValue> args) {
        final var instance = new JsObject();
        instance.setKlass(cls);
        callConstructorChain(cls, instance, args);
        return instance;
    }

    private void callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args) {
        final var constructor = cls.getConstructor();
        if (cls.getSuperClass() == null) {
            initFields(cls, instance);
            if (constructor != null) {
                callFunction(constructor, instance, args);
            }
        } else if (constructor == null) {
            callConstructorChain(cls.getSuperClass(), instance, args);
            initFields(cls, instance);
        } else {
            callFunction(constructor, instance, args);
        }
    }

    private void initFields(JsClass cls, JsObject instance) {
        for (final var field : cls.getInstanceFields()) {
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(instance);
            final var value = field.getValue() == null ? JsUndefined.getInstance() : eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                instance.setPrivate(priv.getName(), value);
            } else {
                final var key = field.isComputed()
                        ? JsCoercion.toStr(eval(field.getKey(), fieldScope))
                        : staticKeyName(field.getKey());
                instance.set(key, value);
            }
        }
    }

    private JsValue evalSuperCall(CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisValue = env.resolveThis();
        if (!(thisValue instanceof JsObject instance)) {
            throw new TypeErrorException("'super' call outside of a constructor");
        }
        final var args = evalArguments(call.getArguments(), env);
        callConstructorChain(home.getSuperClass(), instance, args);
        initFields(home, instance);
        return JsUndefined.getInstance();
    }

    private JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisArg = env.resolveThis();
        final var parent = home.getSuperClass();
        final var key = memberKey(member, env);
        final var args = evalArguments(call.getArguments(), env);
        final var staticContext = thisArg instanceof JsClass;
        final var method = staticContext ? parent.findStaticMethod(key) : parent.findInstanceMethod(key);
        if (method != null) {
            return callFunction(method, thisArg, args);
        }
        final var getter = staticContext ? parent.findStaticGetter(key) : parent.findInstanceGetter(key);
        if (getter != null) {
            return callValue(callFunction(getter, thisArg, List.of()), thisArg, args);
        }
        throw new TypeErrorException("(intermediate value).super." + key + " is not a function");
    }

    private JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var home = superHomeClass(env);
        final var thisArg = env.resolveThis();
        final var parent = home.getSuperClass();
        final var key = memberKey(member, env);
        final var staticContext = thisArg instanceof JsClass;
        final var getter = staticContext ? parent.findStaticGetter(key) : parent.findInstanceGetter(key);
        if (getter != null) {
            return callFunction(getter, thisArg, List.of());
        }
        final var method = staticContext ? parent.findStaticMethod(key) : parent.findInstanceMethod(key);
        if (method != null) {
            return method;
        }
        if (staticContext) {
            return getStaticMember(parent, key);
        }
        return JsUndefined.getInstance();
    }

    private JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls && cls.getSuperClass() != null) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }

    private JsValue getStaticMember(JsClass cls, String key) {
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

    private JsValue getPrivateMember(JsValue target, String name, Environment env) {
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

    private void setPrivateMember(JsValue target, String name, JsValue value, Environment env) {
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

    private JsValue assignToPrivate(MemberExpression member, PrivateIdentifier priv, AssignmentExpression assignment,
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
        final var value = JsOperators.binary(baseOperator(operator), current, eval(assignment.getValue(), env));
        setPrivateMember(target, name, value, env);
        return value;
    }

    private JsValue evalBrandCheck(PrivateIdentifier priv, JsValue target, Environment env) {
        final var name = priv.getName();
        if (target instanceof JsObject object) {
            if (object.hasPrivate(name)) {
                return JsBoolean.TRUE;
            }
            if (env.resolveHomeClass() instanceof JsClass cls && cls.declaresPrivate(name)) {
                return JsBoolean.of(object.getKlass() != null && object.getKlass().isSubclassOf(cls));
            }
        }
        return JsBoolean.FALSE;
    }

    private JsValue evalInstanceof(JsValue left, JsValue right) {
        if (right instanceof JsClass cls) {
            if (left instanceof JsObject object && object.getKlass() != null) {
                return JsBoolean.of(object.getKlass().isSubclassOf(cls));
            }
            return JsBoolean.FALSE;
        }
        if (right instanceof JsFunction || right instanceof JsNativeFunction) {
            return JsBoolean.FALSE;
        }
        throw new TypeErrorException("Right-hand side of 'instanceof' is not callable");
    }

    private JsValue generatorMethod(JsGenerator generator, String key) {
        final var coroutine = generator.getCoroutine();
        return switch (key) {
            case "next" -> new JsNativeFunction("next", (_, args) -> stepResult(coroutine.resumeNext(arg0(args))));
            case "return" ->
                new JsNativeFunction("return", (_, args) -> stepResult(coroutine.resumeReturn(arg0(args))));
            case "throw" -> new JsNativeFunction("throw", (_, args) -> stepResult(coroutine.resumeThrow(arg0(args))));
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue stepResult(Coroutine.StepResult step) {
        return stepResult(step.value(), step.done());
    }

    private JsValue stepResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    private enum AsyncStep {
        NEXT, RETURN, THROW
    }

    private JsValue asyncGeneratorMethod(JsAsyncGenerator generator, String key) {
        return switch (key) {
            case "next" ->
                new JsNativeFunction("next", (_, args) -> driveAsyncGenerator(generator, AsyncStep.NEXT, arg0(args)));
            case "return" -> new JsNativeFunction("return",
                    (_, args) -> driveAsyncGenerator(generator, AsyncStep.RETURN, arg0(args)));
            case "throw" ->
                new JsNativeFunction("throw", (_, args) -> driveAsyncGenerator(generator, AsyncStep.THROW, arg0(args)));
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue driveAsyncGenerator(JsAsyncGenerator generator, AsyncStep kind, JsValue arg) {
        final var coroutine = generator.getCoroutine();
        final var promise = new JsPromise(eventLoop);
        if (coroutine.isDone()) {
            if (kind == AsyncStep.THROW) {
                promise.reject(arg);
            } else {
                promise.resolve(stepResult(kind == AsyncStep.RETURN ? arg : JsUndefined.getInstance(), true));
            }
            return promise;
        }
        generator.setPending(promise);
        try {
            final var step = switch (kind) {
                case NEXT -> coroutine.resumeNext(arg);
                case RETURN -> coroutine.resumeReturn(arg);
                case THROW -> coroutine.resumeThrow(arg);
            };
            if (!coroutine.isDone() && coroutine.pauseReason() == Coroutine.PauseReason.AWAIT) {
                return promise;
            }
            resolveStep(generator, step.value(), step.done());
        } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                | SyntaxErrorException error) {
            rejectStep(generator, error);
        }
        return promise;
    }

    private void observeAsyncGenerator(JsAsyncGenerator generator, RuntimeException escaped) {
        final var coroutine = generator.getCoroutine();
        if (escaped != null) {
            rejectStep(generator, escaped);
        } else if (coroutine.isDone()) {
            resolveStep(generator, coroutine.completedValue(), true);
        } else if (coroutine.pauseReason() == Coroutine.PauseReason.YIELD) {
            resolveStep(generator, coroutine.yieldedValue(), false);
        }
    }

    private void resolveStep(JsAsyncGenerator generator, JsValue value, boolean done) {
        final var promise = generator.clearPending();
        if (promise != null) {
            promise.resolve(stepResult(value, done));
        }
    }

    private void rejectStep(JsAsyncGenerator generator, RuntimeException error) {
        final var promise = generator.clearPending();
        if (promise == null) {
            return;
        }
        if (error instanceof JsThrowException || error instanceof TypeErrorException
                || error instanceof ReferenceErrorException || error instanceof RangeErrorException
                || error instanceof SyntaxErrorException) {
            promise.reject(toErrorValue(error));
        } else {
            throw error;
        }
    }

    private JsValue regExpMember(JsRegExp regexp, String key) {
        final var member = RegexBuiltins.getMethod(regexp, key);
        return member == null ? JsUndefined.getInstance() : member;
    }

    private JsValue promiseMethod(JsPromise promise, String key) {
        return switch (key) {
            case "then" -> new JsNativeFunction("then", (_, args) -> promiseThen(promise, arg0(args), arg1(args)));
            case "catch" ->
                new JsNativeFunction("catch", (_, args) -> promiseThen(promise, JsUndefined.getInstance(), arg0(args)));
            case "finally" -> new JsNativeFunction("finally", (_, args) -> promiseFinally(promise, arg0(args)));
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue promiseThen(JsPromise promise, JsValue onFulfilled, JsValue onRejected) {
        final var derived = new JsPromise(eventLoop);
        promise.subscribe(value -> settleThen(derived, onFulfilled, value, true),
                reason -> settleThen(derived, onRejected, reason, false));
        return derived;
    }

    private void settleThen(JsPromise derived, JsValue handler, JsValue input, boolean fulfilled) {
        if (!(handler instanceof JsFunction) && !(handler instanceof JsNativeFunction)) {
            if (fulfilled) {
                derived.resolve(input);
            } else {
                derived.reject(input);
            }
            return;
        }
        try {
            derived.resolve(callValue(handler, JsUndefined.getInstance(), List.of(input)));
        } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                | SyntaxErrorException error) {
            derived.reject(toErrorValue(error));
        }
    }

    private JsValue promiseFinally(JsPromise promise, JsValue onFinally) {
        final var derived = new JsPromise(eventLoop);
        promise.subscribe(value -> {
            runFinally(onFinally);
            derived.resolve(value);
        }, reason -> {
            runFinally(onFinally);
            derived.reject(reason);
        });
        return derived;
    }

    private void runFinally(JsValue onFinally) {
        if (onFinally instanceof JsFunction || onFinally instanceof JsNativeFunction) {
            callValue(onFinally, JsUndefined.getInstance(), List.of());
        }
    }

    private JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private JsValue arg1(List<JsValue> args) {
        return args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
    }

    private final class Iteration {
        private final JsGenerator generator;
        private final List<JsValue> buffer;
        private int index;

        private Iteration(JsValue iterable) {
            if (iterable instanceof JsGenerator gen) {
                this.generator = gen;
                this.buffer = null;
            } else {
                this.generator = null;
                this.buffer = arrayLikeElements(iterable);
            }
        }

        private JsValue next() {
            if (generator != null) {
                final var step = generator.getCoroutine().resumeNext(JsUndefined.getInstance());
                return step.done() ? null : step.value();
            }
            return index < buffer.size() ? buffer.get(index++) : null;
        }

        private void close() {
            if (generator != null && !generator.getCoroutine().isDone()) {
                generator.getCoroutine().resumeReturn(JsUndefined.getInstance());
            }
        }
    }
}
