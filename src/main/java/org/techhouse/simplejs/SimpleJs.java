package org.techhouse.simplejs;

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
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
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
    // Parses once, so a stored script can be compiled at save time and re-run without re-parsing. Throws
    // the parse failures run(String, HostBindings) reports as a "SyntaxError" ScriptResult, which is what
    // lets a caller reject an unparseable procedure before it is ever persisted.
    public CompiledScript compile(String source, boolean strictScriptGoal) {
        final var program = Parser.parse(Lexer.lexWithPositions(source), strictScriptGoal);
        return new CompiledScript(program, source, strictScriptGoal, JsonUtils.sha256(source));
    }

    /**
     * The distinct specifiers a compiled program imports or re-exports from, in source order. Only the static
     * forms are visible: a dynamic {@code import(expr)} is an arbitrary expression and cannot be resolved
     * without running the program. Callers use this to report an unresolvable import when a script is
     * installed rather than on somebody else's first call; the AST stays inside this package.
     */
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
        try {
            final var program = Parser.parse(Lexer.lexWithPositions(source), capturing.strictScriptGoal());
            return resultOf(Interpreter.run(program, capturing, null, this::convertResult), capture, limits);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            final var error = describe(failure);
            if (error == null) {
                throw failure;
            }
            return failed(error.name(), error.message(), error.stack(), capture);
        }
    }

    /**
     * Reports a failure that happened before a run could start - a parse error a caller compiled ahead of
     * time - with the same name and message a run of that source would have produced.
     */
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

    /**
     * Runs a compiled program with {@code around} wrapping the module body, so a caller can enclose the whole
     * script in a transaction that begins and commits on the body's own thread.
     */
    public ScriptResult run(CompiledScript compiled, HostBindings host, Interpreter.ModuleBodyWrapper around) {
        final var limits = host.limits();
        final var capture = new ConsoleCapture(
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINES : limits.maxLogLines(),
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS : limits.maxLogLineChars());
        final var capturing = CapturingHostBindings.wrap(host, capture);
        try {
            // The two goals differ in which early errors are raised, so a program parsed under the other
            // one is simply the wrong program - parse again rather than run it.
            final var program = compiled.strictScriptGoal() == capturing.strictScriptGoal()
                    ? compiled.program()
                    : Parser.parse(Lexer.lexWithPositions(compiled.source()), capturing.strictScriptGoal());
            return resultOf(Interpreter.run(program, capturing, around, this::convertResult), capture, limits);
        } catch (RuntimeException | OutOfMemoryError | StackOverflowError failure) {
            final var error = describe(failure);
            if (error == null) {
                throw failure;
            }
            return failed(error.name(), error.message(), error.stack(), capture);
        }
    }

    /**
     * Opens a callable a pipeline step invokes once per document. The module body is evaluated here, once, and
     * the interpreter stays alive until {@link ScriptCallable#close()} - so the whole sequence of calls shares
     * one instruction budget, deadline and memory budget rather than getting a fresh one per row.
     *
     * <p>
     * The callable is the module's {@code export default}, else its top-level {@code return} value. An async or
     * generator function is refused: the call must not hop off the thread holding the collection read locks.
     */
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

    private record SessionCallable(Interpreter.Session session, JsValue function) implements ScriptCallable {
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

        // The document (and the fold's accumulator) is charged before the call and released after it, so a
        // scan of a million documents costs one document of the memory budget rather than a million.
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

    // The one place a failure becomes a reportable name+message, shared by run(), openCallable() and every
    // per-document call. A null means the throwable is not the engine's to report, so it propagates.
    private static ScriptError describe(Throwable failure) {
        return switch (failure) {
            case ScriptCancelledException cancelled -> new ScriptError("ScriptCancelledError", cancelled.getMessage());
            case ScriptTimeoutException timeout -> new ScriptError("ScriptTimeoutError", timeout.getMessage());
            case ScriptMemoryException memory -> new ScriptError("ScriptMemoryError", memory.getMessage());
            case ScriptAbortException limit -> new ScriptError("ScriptLimitError", limit.getMessage());
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
                new ScriptError("ScriptPendingResultError", error.getMessage(), error.getCapturedStack());
            case SimpleJsRuntimeException error ->
                new ScriptError("InternalError", error.getMessage(), error.getCapturedStack());
            // A deliberate, narrow exception to "never catch these": the throw originates at one
            // script-driven allocation (or one runaway recursion in the regex matcher / JSON parser,
            // neither of which passes through the interpreter's depth counter), the oversized object
            // becomes garbage on the way out, and the alternative is an unhandled error killing the
            // connection's thread. The budget is what should make this unreachable; reaching it
            // may also mean the JVM was already under pressure from something other than this script,
            // which is why ScriptOperationHelper logs it at WARN rather than treating it as routine.
            case OutOfMemoryError _ -> new ScriptError("ScriptMemoryError", "Script exhausted available memory");
            case StackOverflowError _ -> new ScriptError("ScriptMemoryError", "Script exhausted available memory");
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

    // The cap belongs with the other sandbox limits rather than with the caller, so CALL_PROCEDURE
    // inherits it; an unlimited budget skips the estimation walk entirely.
    private ScriptResult resultOf(JsonBaseElement value, ConsoleCapture capture, ResourceLimits limits) {
        final var element = value == null ? JsonNull.INSTANCE : value;
        final var max = limits == null ? -1 : limits.maxResultBytes();
        if (max >= 0) {
            final var size = EJsonInterop.estimatedBytes(element);
            if (size > max) {
                return failed("ScriptResultTooLargeError",
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

    // Runs while the interpreter is still alive so an accessor-valued property is read through its getter
    // rather than dropped, and so the getter's own work is charged to the run's budgets.
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

    // The event loop has already drained, so a promise at the top level is normally settled; one that is
    // still pending can never settle, which is an error rather than a null result.
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

    // A thrown value may have no "name" anywhere on its prototype chain (e.g. the test262 harness's
    // Test262Error, a plain function constructor whose prototype only defines `toString`); fall back
    // to the constructor function's own name before defaulting to "Error".
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

    // An error instance carries only the properties the constructor set: `name` normally lives on
    // the intrinsic prototype, so the chain has to be walked to report it.
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
