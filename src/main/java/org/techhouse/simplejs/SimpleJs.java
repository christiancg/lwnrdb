package org.techhouse.simplejs;

import static org.techhouse.simplejs.host.ScriptErrorNames.CANCELLED;
import static org.techhouse.simplejs.host.ScriptErrorNames.EXHAUSTED_MEMORY_MESSAGE;
import static org.techhouse.simplejs.host.ScriptErrorNames.LIMIT;
import static org.techhouse.simplejs.host.ScriptErrorNames.MEMORY;
import static org.techhouse.simplejs.host.ScriptErrorNames.PENDING_RESULT;
import static org.techhouse.simplejs.host.ScriptErrorNames.RESULT_TOO_LARGE;
import static org.techhouse.simplejs.host.ScriptErrorNames.TIMEOUT;

import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.exceptions.ScriptCancelledException;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
import org.techhouse.simplejs.exceptions.ScriptPendingResultException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.exceptions.UnsupportedNodeException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.host.CapturingHostBindings;
import org.techhouse.simplejs.host.ConsoleCapture;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.ScriptRunMetrics;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.internal.Session;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.utils.JsonUtils;

public final class SimpleJs {
    public CompiledScript compile(String source, boolean strictScriptGoal) {
        final var program = Parser.parse(Lexer.lexWithPositions(source), strictScriptGoal);
        return new CompiledScript(program, source, strictScriptGoal, JsonUtils.sha256(source));
    }

    public List<String> moduleSpecifiers(CompiledScript compiled) {
        final var specifiers = new LinkedHashSet<String>();
        for (final var statement : compiled.program().getBody()) {
            final var source = switch (statement) {
                case ImportDeclaration declaration -> declaration.getSource();
                case ExportNamedDeclaration declaration -> declaration.getSource();
                case ExportAllDeclaration declaration -> declaration.getSource();
                default -> null;
            };
            if (source != null && source.getValue() != null) {
                specifiers.add(source.getValue());
            }
        }
        return List.copyOf(specifiers);
    }

    public ScriptResult run(String source, HostBindings host) {
        final var limits = host.limits();
        final var capture = new ConsoleCapture(
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINES : limits.maxLogLines(),
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS : limits.maxLogLineChars());
        final var capturing = CapturingHostBindings.wrap(host, capture);
        final var metrics = new Interpreter.RunMetrics();
        try {
            final var program = Parser.parse(Lexer.lexWithPositions(source), capturing.strictScriptGoal());
            return resultOf(Interpreter.run(program, capturing, null, this::convertResult, metrics), capture, limits)
                    .withMetrics(measured(metrics));
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            final var error = describe(failure);
            if (error == null) {
                throw failure;
            }
            return failed(error.name(), error.message(), error.stack(), capture).withMetrics(measured(metrics));
        }
    }

    public ScriptResult failure(RuntimeException failure) {
        final var error = describe(failure);
        if (error == null) {
            throw failure;
        }
        return ScriptResult.error(error.name(), error.message(), error.stack(), List.of(), false);
    }

    public ScriptResult run(CompiledScript compiled, HostBindings host) {
        return run(compiled, host, null);
    }

    public ScriptResult run(CompiledScript compiled, HostBindings host, Interpreter.ModuleBodyWrapper around) {
        final var limits = host.limits();
        final var capture = new ConsoleCapture(
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINES : limits.maxLogLines(),
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS : limits.maxLogLineChars());
        final var capturing = CapturingHostBindings.wrap(host, capture);
        final var metrics = new Interpreter.RunMetrics();
        try {
            final var program = compiled.strictScriptGoal() == capturing.strictScriptGoal()
                    ? compiled.program()
                    : Parser.parse(Lexer.lexWithPositions(compiled.source()), capturing.strictScriptGoal());
            return resultOf(Interpreter.run(program, capturing, around, this::convertResult, metrics), capture, limits)
                    .withMetrics(measured(metrics));
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            final var error = describe(failure);
            if (error == null) {
                throw failure;
            }
            return failed(error.name(), error.message(), error.stack(), capture).withMetrics(measured(metrics));
        }
    }

    private static ScriptRunMetrics measured(Interpreter.RunMetrics metrics) {
        return new ScriptRunMetrics(metrics.instructions(), metrics.instructionBudget(), metrics.peakMemoryBytes(),
                metrics.memoryBudget(), 0L, 0L);
    }

    public ScriptCallable openCallable(String source, HostBindings host) {
        try {
            return openCallable(compile(source, host.strictScriptGoal()), host);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            throw asCallableException(failure);
        }
    }

    public ScriptCallable openCallable(CompiledScript compiled, HostBindings host) {
        try {
            final var program = compiled.strictScriptGoal() == host.strictScriptGoal()
                    ? compiled.program()
                    : Parser.parse(Lexer.lexWithPositions(compiled.source()), host.strictScriptGoal());
            final var session = Interpreter.open(program, host);
            try {
                return new SessionCallable(session, requireCallable(session.outcome()));
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
                session.close();
                throw failure;
            }
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            throw asCallableException(failure);
        }
    }

    private JsValue requireCallable(Interpreter.ProgramOutcome outcome) {
        final var candidate = outcome.hasReturn() ? outcome.returnValue() : outcome.exportDefault();
        if (candidate == null) {
            throw new TypeErrorException("Script must export default (or return) a function");
        }
        if (candidate instanceof JsFunction function) {
            if (function.isAsync() || function.isGenerator()) {
                throw new TypeErrorException("Script function must not be async or a generator");
            }
            return function;
        }
        if (candidate instanceof JsNativeFunction || candidate instanceof JsClass) {
            return candidate;
        }
        throw new TypeErrorException("Script must export default (or return) a function");
    }

    private static ScriptCallableException asCallableException(Throwable failure) {
        final var error = describe(failure);
        if (error == null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) failure;
        }
        return new ScriptCallableException(error.name(), error.message(), error.stack());
    }

    private record SessionCallable(Session session, JsValue function) implements ScriptCallable {
        @Override
        public JsonBaseElement apply(JsonObject document) {
            return invoke(List.of(EJsonInterop.fromEjson(document)), document, null);
        }

        @Override
        public JsonBaseElement apply(JsonBaseElement accumulator, JsonObject document) {
            return invoke(List.of(EJsonInterop.fromEjson(accumulator), EJsonInterop.fromEjson(document)), document,
                    accumulator);
        }

        @Override
        public JsonBaseElement applyWithContext(JsonObject document, JsonObject context) {
            return invoke(List.of(EJsonInterop.fromEjson(document), EJsonInterop.fromEjson(context)), document,
                    context);
        }

        private JsonBaseElement invoke(List<JsValue> args, JsonObject document, JsonBaseElement accumulator) {
            final var charged = EJsonInterop.estimatedBytes(document)
                    + (accumulator == null ? 0 : EJsonInterop.estimatedBytes(accumulator));
            session.charge(charged);
            try {
                return EJsonInterop.toHostEjson(settled(session.call(function, args)));
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
                throw asCallableException(failure);
            } finally {
                session.release(charged);
            }
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private record ScriptError(String name, String message, List<String> stack) {
        ScriptError(String name, String message) {
            this(name, message, null);
        }
    }

    private static ScriptError describe(Throwable failure) {
        return switch (failure) {
            case ScriptCancelledException cancelled -> new ScriptError(CANCELLED, cancelled.getMessage());
            case ScriptTimeoutException timeout -> new ScriptError(TIMEOUT, timeout.getMessage());
            case ScriptMemoryException memory -> new ScriptError(MEMORY, memory.getMessage());
            case ScriptAbortException limit -> new ScriptError(LIMIT, limit.getMessage());
            case JsThrowException thrown -> throwName(thrown);
            case TypeErrorException error -> new ScriptError("TypeError", error.getMessage(), error.getCapturedStack());
            case ReferenceErrorException error ->
                new ScriptError("ReferenceError", error.getMessage(), error.getCapturedStack());
            case RangeErrorException error ->
                new ScriptError("RangeError", error.getMessage(), error.getCapturedStack());
            case SyntaxErrorException error ->
                new ScriptError("SyntaxError", error.getMessage(), error.getCapturedStack());
            case UnexpectedTokenException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnexpectedEndOfInputException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnexpectedCharacterException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnterminatedStringException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnterminatedTemplateException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnterminatedCommentException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnterminatedRegexException error -> new ScriptError("SyntaxError", error.getMessage());
            case UnsupportedNodeException error ->
                new ScriptError("SyntaxError", "Unsupported syntax: " + error.getMessage());
            case ScriptPendingResultException error ->
                new ScriptError(PENDING_RESULT, error.getMessage(), error.getCapturedStack());
            case SimpleJsRuntimeException error ->
                new ScriptError("InternalError", error.getMessage(), error.getCapturedStack());
            case OutOfMemoryError _ -> new ScriptError(MEMORY, EXHAUSTED_MEMORY_MESSAGE);
            case StackOverflowError _ -> new ScriptError(MEMORY, EXHAUSTED_MEMORY_MESSAGE);
            default -> null;
        };
    }

    private static ScriptError throwName(JsThrowException thrown) {
        final var value = thrown.getValue();
        if (value instanceof JsObject object) {
            return new ScriptError(errorName(object), field(object), object.getErrorStack());
        }
        return new ScriptError("Error", JsCoercion.toStr(value));
    }

    private ScriptResult resultOf(JsonBaseElement value, ConsoleCapture capture, ResourceLimits limits) {
        final var element = value == null ? JsonNull.INSTANCE : value;
        final var max = limits == null ? -1 : limits.maxResultBytes();
        if (max >= 0) {
            final var size = EJsonInterop.estimatedBytes(element);
            if (size > max) {
                return failed(RESULT_TOO_LARGE,
                        "Script result of about " + size + " bytes exceeds the maximum of " + max + " bytes", null,
                        capture);
            }
        }
        return ok(element, capture);
    }

    private ScriptResult ok(JsonBaseElement value, ConsoleCapture capture) {
        return ScriptResult.value(value, capture.lines(), capture.isTruncated());
    }

    private ScriptResult failed(String name, String message, List<String> stack, ConsoleCapture capture) {
        return ScriptResult.error(name, message, stack, capture.lines(), capture.isTruncated());
    }

    private JsonBaseElement convertResult(Interpreter.ProgramOutcome outcome, InterpreterOps ops) {
        return EJsonInterop.toHostEjson(contractResult(outcome), ops);
    }

    private JsValue contractResult(Interpreter.ProgramOutcome outcome) {
        if (outcome.hasReturn()) {
            return settled(outcome.returnValue());
        }
        if (outcome.exportDefault() != null) {
            return settled(outcome.exportDefault());
        }
        if (!outcome.namedExports().isEmpty()) {
            final var object = new JsObject();
            outcome.namedExports().forEach(object::set);
            return object;
        }
        return JsUndefined.getInstance();
    }

    private static JsValue settled(JsValue value) {
        if (!(value instanceof JsPromise promise)) {
            return value;
        }
        promise.markHandled();
        return switch (promise.getState()) {
            case FULFILLED -> promise.getResult();
            case REJECTED -> throw new JsThrowException(promise.getResult());
            case PENDING -> throw new ScriptPendingResultException("The script's result promise never settled");
        };
    }

    private static String errorName(JsObject object) {
        var current = object;
        while (current != null) {
            if (current.has("name")) {
                return JsCoercion.toStr(current.get("name"));
            }
            current = current.getProto() instanceof JsObject proto ? proto : null;
        }
        current = object;
        while (current != null) {
            if (current.has("constructor")) {
                final var ctorName = constructorName(current.get("constructor"));
                if (ctorName != null) {
                    return ctorName;
                }
            }
            current = current.getProto() instanceof JsObject proto ? proto : null;
        }
        return "Error";
    }

    private static String constructorName(JsValue constructorValue) {
        if (constructorValue instanceof JsFunction function) {
            return function.getName();
        }
        if (constructorValue instanceof JsNativeFunction function) {
            return function.getName();
        }
        if (constructorValue instanceof JsClass klass) {
            return klass.getName();
        }
        return null;
    }

    private static String field(JsObject object) {
        var current = object;
        while (current != null) {
            if (current.has("message")) {
                return JsCoercion.toStr(current.get("message"));
            }
            current = current.getProto() instanceof JsObject proto ? proto : null;
        }
        return "";
    }
}
