package org.techhouse.simplejs;

import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptAbortException;
import org.techhouse.simplejs.exceptions.ScriptTimeoutException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;
import org.techhouse.simplejs.host.HostBindings;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class SimpleJs {
    public ScriptResult run(String source, HostBindings host) {
        try {
            final var program = Parser.parse(Lexer.lexWithPositions(source));
            final var outcome = Interpreter.run(program, host);
            final var value = EJsonInterop.toEjson(contractResult(outcome));
            return ScriptResult.value(value == null ? JsonNull.INSTANCE : value);
        } catch (ScriptTimeoutException timeout) {
            return ScriptResult.error("ScriptTimeoutError", timeout.getMessage());
        } catch (ScriptAbortException limit) {
            return ScriptResult.error("ScriptLimitError", limit.getMessage());
        } catch (JsThrowException thrown) {
            return errorFromThrow(thrown);
        } catch (TypeErrorException error) {
            return ScriptResult.error("TypeError", error.getMessage());
        } catch (ReferenceErrorException error) {
            return ScriptResult.error("ReferenceError", error.getMessage());
        } catch (RangeErrorException error) {
            return ScriptResult.error("RangeError", error.getMessage());
        } catch (SyntaxErrorException | UnexpectedTokenException | UnexpectedEndOfInputException
                | UnexpectedCharacterException | UnterminatedStringException | UnterminatedTemplateException
                | UnterminatedCommentException | UnterminatedRegexException error) {
            return ScriptResult.error("SyntaxError", error.getMessage());
        }
    }

    private JsValue contractResult(Interpreter.ProgramOutcome outcome) {
        if (outcome.hasReturn()) {
            return outcome.returnValue();
        }
        if (outcome.exportDefault() != null) {
            return outcome.exportDefault();
        }
        if (!outcome.namedExports().isEmpty()) {
            final var object = new JsObject();
            outcome.namedExports().forEach(object::set);
            return object;
        }
        return JsUndefined.getInstance();
    }

    private ScriptResult errorFromThrow(JsThrowException thrown) {
        final var value = thrown.getValue();
        if (value instanceof JsObject object) {
            final var name = object.has("name") ? JsCoercion.toStr(object.get("name")) : "Error";
            final var message = object.has("message") ? JsCoercion.toStr(object.get("message")) : "";
            return ScriptResult.error(name, message);
        }
        return ScriptResult.error("Error", JsCoercion.toStr(value));
    }
}
