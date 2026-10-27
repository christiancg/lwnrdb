package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.collectBoundNames;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;

public record FunctionShape(List<JsNode> params, String sourceText, List<String> paramNames, boolean simpleParams,
        boolean usesArguments) {

    static FunctionShape of(List<JsNode> params, String sourceText) {
        final var names = new ArrayList<String>(params.size());
        var simple = true;
        for (final var param : params) {
            simple &= param instanceof Identifier;
            collectBoundNames(param, names);
        }
        final var usesArguments = sourceText == null || sourceText.contains("\\u") || sourceText.contains("arguments");
        return new FunctionShape(params, sourceText, List.copyOf(names), simple, usesArguments);
    }

    boolean describes(List<JsNode> otherParams, String otherSourceText) {
        return params == otherParams && Objects.equals(sourceText, otherSourceText);
    }
}
