package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.List;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class FunctionFactory {
    private final Interpreter interpreter;

    FunctionFactory(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    JsValue evalFunctionExpression(FunctionExpression expression, Environment env) {
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

    JsValue evalArrowFunction(ArrowFunctionExpression expression, Environment env) {
        return makeFunction(null, expression.getParams(), expression.getBody(), true, expression.isExpressionBody(),
                expression.isAsync(), false, env, expression.getSourceText());
    }

    JsFunction makeFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure, String sourceText) {
        final var function = new JsFunction(name, params, body, arrow, expressionBody, async, generator, closure);
        function.setSourceText(sourceText);
        function.setModuleName(interpreter.callStack.currentModule());
        if (generator) {
            function.getPrototype()
                    .setProto(async ? interpreter.intrinsics.asyncIteratorProto : interpreter.intrinsics.iteratorProto);
        }
        return function;
    }

    JsValue evalCall(CallExpression call, Environment env) {
        final var callee = call.getCallee();
        if (callee instanceof SuperExpression) {
            return interpreter.classes.evalSuperCall(call, env);
        }
        var thisArg = (JsValue) JsUndefined.getInstance();
        final JsValue function;
        if (callee instanceof MemberExpression member) {
            if (member.getObject() instanceof SuperExpression) {
                return interpreter.classes.evalSuperMemberCall(member, call, env);
            }
            final var object = interpreter.memberAccess.evalChainObject(member.getObject(), env);
            if (object == Interpreter.SHORT_CIRCUIT) {
                return Interpreter.SHORT_CIRCUIT;
            }
            if (member.isOptional() && isNullish(object)) {
                return Interpreter.SHORT_CIRCUIT;
            }
            thisArg = object;
            if (member.getProperty() instanceof PrivateIdentifier priv) {
                function = interpreter.privateMembers.getPrivateMember(object, priv.getName(), env);
            } else {
                function = interpreter.memberIo.getMemberByKey(object,
                        interpreter.memberAccess.memberKeyValue(member, env));
            }
        } else {
            final var callable = interpreter.memberAccess.evalChainObject(callee, env);
            if (callable == Interpreter.SHORT_CIRCUIT) {
                return Interpreter.SHORT_CIRCUIT;
            }
            function = callable;
        }
        if (call.isOptional() && isNullish(function)) {
            return Interpreter.SHORT_CIRCUIT;
        }
        return interpreter.functions.callValue(function, thisArg,
                interpreter.functions.evalArguments(call.getArguments(), env));
    }
}
