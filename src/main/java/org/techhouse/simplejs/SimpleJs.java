package org.techhouse.simplejs;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
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

    public ScriptResult run(String source, HostBindings host) {
        final var limits = host.limits();
        final var capture = new ConsoleCapture(
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINES : limits.maxLogLines(),
                limits == null ? ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS : limits.maxLogLineChars());
        final var capturing = CapturingHostBindings.wrap(host, capture);
        try {
            final var program = Parser.parse(Lexer.lexWithPositions(source), capturing.strictScriptGoal());
            final var outcome = Interpreter.run(program, capturing);
            final var value = EJsonInterop.toHostEjson(contractResult(outcome));
            return ok(value == null ? JsonNull.INSTANCE : value, capture);
        } catch (ScriptTimeoutException timeout) {
            return failed("ScriptTimeoutError", timeout.getMessage(), capture);
        } catch (ScriptMemoryException memory) {
            return failed("ScriptMemoryError", memory.getMessage(), capture);
        } catch (ScriptAbortException limit) {
            return failed("ScriptLimitError", limit.getMessage(), capture);
        } catch (JsThrowException thrown) {
            return errorFromThrow(thrown, capture);
        } catch (TypeErrorException error) {
            return failed("TypeError", error.getMessage(), capture);
        } catch (ReferenceErrorException error) {
            return failed("ReferenceError", error.getMessage(), capture);
        } catch (RangeErrorException error) {
            return failed("RangeError", error.getMessage(), capture);
        } catch (SyntaxErrorException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException error) {
            return failed("SyntaxError", error.getMessage(), capture);
        } catch (UnsupportedNodeException error) {
            return failed("SyntaxError", "Unsupported syntax: " + error.getMessage(), capture);
        } catch (SimpleJsRuntimeException error) {
            return failed("InternalError", error.getMessage(), capture);
        } catch (OutOfMemoryError | StackOverflowError exhausted) {
            // A deliberate, narrow exception to "never catch these": the throw originates at one
            // script-driven allocation (or one runaway recursion in the regex matcher / JSON parser,
            // neither of which passes through the interpreter's depth counter), the oversized object
            // becomes garbage on the way out, and the alternative is an unhandled error killing the
            // connection's thread. The budget above is what should make this unreachable; reaching it
            // may also mean the JVM was already under pressure from something other than this script,
            // which is why ScriptOperationHelper logs it at WARN rather than treating it as routine.
            return failed("ScriptMemoryError", "Script exhausted available memory", capture);
        }
    }

    public ScriptResult run(CompiledScript compiled, HostBindings host) {
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
            final var outcome = Interpreter.run(program, capturing);
            final var value = EJsonInterop.toHostEjson(contractResult(outcome));
            return ok(value == null ? JsonNull.INSTANCE : value, capture);
        } catch (ScriptTimeoutException timeout) {
            return failed("ScriptTimeoutError", timeout.getMessage(), capture);
        } catch (ScriptMemoryException memory) {
            return failed("ScriptMemoryError", memory.getMessage(), capture);
        } catch (ScriptAbortException limit) {
            return failed("ScriptLimitError", limit.getMessage(), capture);
        } catch (JsThrowException thrown) {
            return errorFromThrow(thrown, capture);
        } catch (TypeErrorException error) {
            return failed("TypeError", error.getMessage(), capture);
        } catch (ReferenceErrorException error) {
            return failed("ReferenceError", error.getMessage(), capture);
        } catch (RangeErrorException error) {
            return failed("RangeError", error.getMessage(), capture);
        } catch (SyntaxErrorException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException error) {
            return failed("SyntaxError", error.getMessage(), capture);
        } catch (UnsupportedNodeException error) {
            return failed("SyntaxError", "Unsupported syntax: " + error.getMessage(), capture);
        } catch (SimpleJsRuntimeException error) {
            return failed("InternalError", error.getMessage(), capture);
        } catch (OutOfMemoryError | StackOverflowError exhausted) {
            return failed("ScriptMemoryError", "Script exhausted available memory", capture);
        }
    }

    private ScriptResult ok(JsonBaseElement value, ConsoleCapture capture) {
        return ScriptResult.value(value, capture.lines(), capture.isTruncated());
    }

    private ScriptResult failed(String name, String message, ConsoleCapture capture) {
        return ScriptResult.error(name, message, capture.lines(), capture.isTruncated());
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

    // The event loop has already drained, so a promise at the top level is normally settled; one that
    // never settles inside the sandbox contributes undefined.
    private JsValue settled(JsValue value) {
        if (!(value instanceof JsPromise promise)) {
            return value;
        }
        promise.markHandled();
        return switch (promise.getState()) {
            case FULFILLED -> promise.getResult();
            case REJECTED -> throw new JsThrowException(promise.getResult());
            case PENDING -> JsUndefined.getInstance();
        };
    }

    private ScriptResult errorFromThrow(JsThrowException thrown, ConsoleCapture capture) {
        final var value = thrown.getValue();
        if (value instanceof JsObject object) {
            return failed(errorName(object), field(object), capture);
        }
        return failed("Error", JsCoercion.toStr(value), capture);
    }

    // A thrown value may have no "name" anywhere on its prototype chain (e.g. the test262 harness's
    // Test262Error, a plain function constructor whose prototype only defines `toString`); fall back
    // to the constructor function's own name before defaulting to "Error".
    private String errorName(JsObject object) {
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

    private String constructorName(JsValue constructorValue) {
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
    private String field(JsObject object) {
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
