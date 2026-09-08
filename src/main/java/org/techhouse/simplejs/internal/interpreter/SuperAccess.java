package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class SuperAccess {
    private final Interpreter interp;
    private final ClassEvaluator classEvaluator;

    SuperAccess(Interpreter interp, ClassEvaluator classEvaluator) {
        this.interp = interp;
        this.classEvaluator = classEvaluator;
    }

    JsValue evalSuperCall(CallExpression call, Environment env) {
        final var home = superHomeClass(env);
        final var thisValue = env.resolveThisBeforeSuper();
        if (!(thisValue instanceof JsObject instance)) {
            throw new TypeErrorException("'super' call outside of a constructor");
        }
        final var args = interp.evalArguments(call.getArguments(), env);
        if (!InterpreterUtils.isConstructor(home.getProto())) {
            throw new TypeErrorException("Super constructor " + JsCoercion.toStr(home.getProto()) + " of "
                    + home.getName() + " is not a constructor");
        }
        final JsValue self = home.getSuperClass() == null
                ? classEvaluator.applySuperConstructor(home.getSuperConstructor(), instance, args,
                        env.resolveNewTarget())
                : classEvaluator.callConstructorChain(home.getSuperClass(), instance, args, env.resolveNewTarget());
        if (env.isThisInitialized()) {
            throw new ReferenceErrorException("Super constructor may only be called once");
        }
        if (self != instance) {
            env.replaceThis(self);
        }
        env.markThisInitialized();
        final var fieldTarget = classEvaluator.unwrapForFieldInit(self);
        if (fieldTarget != null) {
            classEvaluator.initFields(home, self, fieldTarget);
        }
        return self;
    }

    JsValue evalSuperMemberCall(MemberExpression member, CallExpression call, Environment env) {
        final var thisArg = env.resolveThis();
        final var start = thisArg instanceof JsClass cls ? cls.getProto() : superProtoStart(env);
        final var rawKey = interp.memberKeyValue(member, env);
        final var args = interp.evalArguments(call.getArguments(), env);
        final var value = superRead(start, rawKey, thisArg);
        if (isCallable(value)) {
            return interp.callValue(value, thisArg, args);
        }
        throw new TypeErrorException("(intermediate value).super." + keyDisplay(rawKey) + " is not a function");
    }

    JsValue superRead(JsValue start, JsValue rawKey, JsValue receiver) {
        if (rawKey instanceof JsSymbol symbol) {
            return superProtoSymbolRead(start, symbol, receiver);
        }
        return superProtoRead(start, JsCoercion.toStr(rawKey, interp.ops()), receiver);
    }

    String keyDisplay(JsValue rawKey) {
        return rawKey instanceof JsSymbol symbol
                ? "Symbol(" + (symbol.getDescription() == null ? "" : symbol.getDescription()) + ")"
                : JsCoercion.toStr(rawKey, interp.ops());
    }

    JsValue superProtoSymbolRead(JsValue start, JsSymbol symbol, JsValue receiver) {
        var link = start;
        var synthesised = false;
        while (link != null) {
            if (link instanceof JsObject object) {
                if (object.hasSymbolAccessor(symbol)) {
                    final var getter = object.getSymbolAccessorGetter(symbol);
                    return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, receiver, List.of());
                }
                if (object.hasSymbol(symbol)) {
                    return object.getSymbol(symbol);
                }
            }
            final var next = link.getProto();
            if (next == null && !synthesised && !(link instanceof JsObject)) {
                link = interp.intrinsics().protoFor(link);
                synthesised = true;
                continue;
            }
            link = next;
        }
        return JsUndefined.getInstance();
    }

    void evalSuperMemberWrite(MemberExpression member, JsValue value, Environment env) {
        final var thisArg = env.resolveThis();
        final var key = interp.memberKey(member, env);
        if (thisArg instanceof JsClass cls) {
            final var base = cls.getProto();
            if (base == null) {
                throw new TypeErrorException("Cannot set properties of null (setting '" + key + "')");
            }
            if (!interp.members().setMember(base, key, value, thisArg)) {
                throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
            }
            return;
        }
        final var start = superProtoStart(env, true);
        final var target = start == null ? interp.intrinsics().objectProto : start;
        if (!interp.members().setMember(target, key, value, thisArg)) {
            throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
        }
    }

    JsValue evalSuperMemberAssign(MemberExpression member, Expression valueExpr, Environment env) {
        final var thisArg = env.resolveThis();
        if (thisArg instanceof JsClass cls) {
            final var rawKey = interp.memberKeyValue(member, env);
            final var base = cls.getProto();
            final var value = interp.eval(valueExpr, env);
            final var key = JsCoercion.toStr(rawKey, interp.ops());
            if (base == null) {
                throw new TypeErrorException("Cannot set properties of null (setting '" + key + "')");
            }
            if (!interp.members().setMember(base, key, value, thisArg)) {
                throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
            }
            return value;
        }
        final var start = superProtoStart(env, false);
        final var rawKey = interp.memberKeyValue(member, env);
        final var value = interp.eval(valueExpr, env);
        final var key = JsCoercion.toStr(rawKey, interp.ops());
        final var target = start == null ? interp.intrinsics().objectProto : start;
        if (!interp.members().setMember(target, key, value, thisArg)) {
            throw new TypeErrorException("Cannot assign to read only property 'super." + key + "'");
        }
        return value;
    }

    JsValue evalSuperMemberRead(MemberExpression member, Environment env) {
        final var thisArg = env.resolveThis();
        final var start = thisArg instanceof JsClass cls ? cls.getProto() : superProtoStart(env);
        final var rawKey = interp.memberKeyValue(member, env);
        return superRead(start, rawKey, thisArg);
    }

    JsValue superProtoRead(JsValue start, String key, JsValue thisArg) {
        if (start == null) {
            throw new TypeErrorException("Cannot read properties of null (reading '" + key + "')");
        }
        final var found = interp.members().chainMember(start, key, thisArg);
        return found == null ? JsUndefined.getInstance() : found;
    }

    JsValue superProtoStart(Environment env) {
        return superProtoStart(env, true);
    }

    JsValue superProtoStart(Environment env, boolean throwOnNullHeritage) {
        final var home = env.resolveHomeClass();
        if (home instanceof JsObject object) {
            if (object.isProtoExplicitlyNull()) {
                if (throwOnNullHeritage) {
                    throw new TypeErrorException("Cannot read properties of null (reading '" + "')");
                }
                return null;
            }
            final var proto = object.getProto();
            return proto == null ? interp.intrinsics().objectProto : proto;
        }
        if (home instanceof JsClass cls) {
            if (cls.hasNullHeritage()) {
                if (throwOnNullHeritage) {
                    throw new TypeErrorException("Cannot read properties of null (reading '" + "')");
                }
                return null;
            }
            final var start = cls.getPrototype().getProto();
            return start == null ? interp.intrinsics().objectProto : start;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here: ");
    }

    JsClass superHomeClass(Environment env) {
        if (env.resolveHomeClass() instanceof JsClass cls && cls.isDerived()) {
            return cls;
        }
        throw new SyntaxErrorException("'super' keyword unexpected here");
    }
}
