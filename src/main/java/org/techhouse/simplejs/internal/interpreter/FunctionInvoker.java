package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.ScriptLimitException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Completion;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.YieldExpression;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class FunctionInvoker {
    public final Interpreter interp;

    public FunctionInvoker(Interpreter interp) {
        this.interp = interp;
    }

    public List<JsValue> evalArguments(List<Expression> arguments, Environment env) {
        final var values = new ArrayList<JsValue>();
        for (final var argument : arguments) {
            if (argument instanceof SpreadElement spread) {
                interp.expressions.spreadInto(values, interp.eval(spread.getArgument(), env));
            } else {
                values.add(interp.eval(argument, env));
            }
        }
        return values;
    }

    public JsValue callValue(JsValue callee, JsValue thisArg, List<JsValue> args) {
        interp.tick();
        return switch (callee) {
            case JsProxy proxy -> interp.proxies.apply(proxy, thisArg, args);
            case JsFunction function -> callFunction(function, thisArg, args);
            case JsNativeFunction nativeFunction -> invokeNative(nativeFunction, thisArg, args);
            case JsObject object when object == interp.intrinsics.functionProto -> JsUndefined.getInstance();
            default -> throw new TypeErrorException(JsCoercion.toStr(callee) + " is not a function");
        };
    }

    public JsValue invokeNative(JsNativeFunction nativeFunction, JsValue thisArg, List<JsValue> args) {
        interp.callStack.push(nativeFunction.getName(), CallStack.NATIVE_MODULE);
        try {
            return nativeFunction.invoke(thisArg, args);
        } finally {
            interp.callStack.pop();
        }
    }

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args) {
        return callFunction(function, thisArg, args, JsUndefined.getInstance());
    }

    public JsValue callFunction(JsFunction function, JsValue thisArg, List<JsValue> args, JsValue newTarget) {
        if (interp.maxDepth >= 0 && interp.depth >= interp.maxDepth) {
            throw new ScriptLimitException("Script exceeded its maximum call depth");
        }
        interp.depth++;
        interp.callStack.push(function.getName(), function.getModuleName());
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
            if (function.isAsync() && !function.isGenerator()) {
                return runAsync(function, activation, args);
            }
            interp.binding.bindParams(function.getParams(), args, activation);
            if (function.isAsync()) {
                return makeAsyncGenerator(function, activation);
            }
            if (function.isGenerator()) {
                return makeGenerator(function, activation);
            }
            final var result = runPlainFunction(function, activation);
            if (function.isDerivedConstructor()) {
                if (!isObjectLike(result) && !(result instanceof JsUndefined)) {
                    throw new TypeErrorException("Derived constructors may only return object or undefined");
                }
                if (!isObjectLike(result) && !activation.isThisInitialized()) {
                    throw new ReferenceErrorException(
                            "Must call super constructor before returning from a derived class constructor");
                }
                return isObjectLike(result) ? result : activation.resolveThis();
            }
            return result;
        } finally {
            interp.callStack.pop();
            interp.depth--;
        }
    }

    public JsValue runPlainFunction(JsFunction function, Environment activation) {
        final var saved = interp.currentCoroutine.get();
        interp.currentCoroutine.remove();
        try {
            return runFunctionBody(function, activation);
        } finally {
            if (saved != null) {
                interp.currentCoroutine.set(saved);
            } else {
                interp.currentCoroutine.remove();
            }
        }
    }

    public JsValue runFunctionBody(JsFunction function, Environment activation) {
        if (function.isExpressionBody()) {
            return interp.eval((Expression) function.getBody(), activation);
        }
        final var body = (BlockStatement) function.getBody();
        final var bodyEnv = bodyEnvironment(function, activation, body);
        interp.hoist(body.getBody(), bodyEnv);
        final var completion = interp.statements.blockDeclaresUsing(body.getBody())
                ? interp.statements.runDisposing(bodyEnv,
                        () -> interp.statements.execStatements(body.getBody(), bodyEnv))
                : interp.statements.execStatements(body.getBody(), bodyEnv);
        return completion.kind() == Completion.Kind.RETURN ? completion.value() : JsUndefined.getInstance();
    }

    public Environment bodyEnvironment(JsFunction function, Environment activation, BlockStatement body) {
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

    public boolean hasParameterExpressions(List<JsNode> params) {
        for (final var param : params) {
            if (!(param instanceof Identifier)) {
                return true;
            }
        }
        return false;
    }

    public JsValue makeGenerator(JsFunction function, Environment activation) {
        final var coroutine = new Coroutine();
        interp.coroutines.add(coroutine);
        coroutine.markGenerator();
        ownStackSegment(coroutine, function);
        coroutine.prime(() -> {
            interp.currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        final var generator = new JsGenerator(coroutine);
        final var proto = function.getPrototype();
        generator.setProto(proto instanceof JsObject object ? object : interp.intrinsics.iteratorProto);
        return generator;
    }

    public JsValue runAsync(JsFunction function, Environment activation, List<JsValue> args) {
        final var promise = new JsPromise(interp.eventLoop);
        final var coroutine = new Coroutine();
        interp.coroutines.add(coroutine);
        coroutine.markAsync();
        ownStackSegment(coroutine, function);
        coroutine.startAsync(() -> {
            interp.currentCoroutine.set(coroutine);
            try {
                interp.binding.bindParams(function.getParams(), args, activation);
                promise.resolve(runFunctionBody(function, activation));
            } catch (JsThrowException | TypeErrorException | ReferenceErrorException | RangeErrorException
                    | SyntaxErrorException error) {
                promise.reject(toErrorValue(error, interp.intrinsics));
            }
            return JsUndefined.getInstance();
        });
        return promise;
    }

    public JsValue makeAsyncGenerator(JsFunction function, Environment activation) {
        final var coroutine = new Coroutine();
        interp.coroutines.add(coroutine);
        final var generator = new JsAsyncGenerator(coroutine);
        coroutine.markAsync();
        coroutine.markGenerator();
        ownStackSegment(coroutine, function);
        coroutine.setResumeObserver(escaped -> interp.members.observeAsyncGenerator(generator, escaped));
        coroutine.prime(() -> {
            interp.currentCoroutine.set(coroutine);
            return runFunctionBody(function, activation);
        });
        final var proto = function.getPrototype();
        generator.setProto(proto instanceof JsObject object ? object : interp.intrinsics.asyncIteratorProto);
        return generator;
    }

    public void ownStackSegment(Coroutine coroutine, JsFunction function) {
        final var segment = interp.callStack.segmentFor(function.getName(), function.getModuleName());
        coroutine.setAroundResume(resume -> {
            final var saved = interp.callStack.swap(segment);
            try {
                resume.run();
            } finally {
                interp.callStack.swap(saved);
            }
        });
    }

    public JsValue evalYield(YieldExpression yield, Environment env) {
        final var coroutine = interp.currentCoroutine.get();
        if (coroutine == null || !coroutine.isYieldAllowed()) {
            throw new SyntaxErrorException("yield is only valid inside a generator");
        }
        if (yield.isDelegate()) {
            return YieldDelegation.run(interp, coroutine, interp.eval(yield.getArgument(), env));
        }
        final var value = yield.getArgument() == null
                ? JsUndefined.getInstance()
                : interp.eval(yield.getArgument(), env);
        return coroutine.yieldOut(value);
    }

    public JsValue evalAwait(AwaitExpression await, Environment env) {
        final var coroutine = interp.currentCoroutine.get();
        if (coroutine == null || !coroutine.isAsync()) {
            throw new SyntaxErrorException("await is only valid inside an async function");
        }
        return coroutine.await(toPromise(interp.eval(await.getArgument(), env)));
    }

    public JsPromise toPromise(JsValue value) {
        if (value instanceof JsPromise promise) {
            interp.getMemberByKey(promise, new JsString("constructor"));
            return promise;
        }
        final var promise = new JsPromise(interp.eventLoop);
        promise.resolve(value);
        return promise;
    }

    public JsArguments makeArguments(List<JsValue> args) {
        return withOwnProperties(new JsArguments(args, null, null));
    }

    public JsArguments withOwnProperties(JsArguments arguments) {
        interp.intrinsics.poison(arguments, "callee");
        final var table = arguments.ownProperties();
        table.setFlags("callee", new JsObject.PropertyFlags(false, false, false));
        table.defineSymbolValue(JsSymbol.ITERATOR, interp.intrinsics.arrayProto.getSymbol(JsSymbol.ITERATOR));
        table.setSymbolFlags(JsSymbol.ITERATOR, JsObject.PropertyFlags.HIDDEN);
        return arguments;
    }

}
