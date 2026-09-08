package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.*;

import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsValue;

public final class HasMemberEvaluator {
    public final Interpreter interp;

    public HasMemberEvaluator(Interpreter interp) {
        this.interp = interp;
    }

    public boolean hasMember(JsValue container, JsValue keyValue) {
        return switch (container) {
            case JsProxy proxy -> interp.proxies.has(proxy, JsCoercion.toPropertyKey(keyValue, interp.ops));
            case JsGlobalObject global -> global.getEnv().isDeclared(JsCoercion.toStr(keyValue));
            case JsObject object when keyValue instanceof JsSymbol symbol -> hasSymbolMember(object, symbol);
            case JsObject object -> hasStringMember(object, JsCoercion.toStr(keyValue));
            case JsClass cls when keyValue instanceof JsSymbol symbol -> hasStaticSymbolMember(cls, symbol);
            case JsClass cls -> hasStaticMember(cls, JsCoercion.toStr(keyValue));
            case JsArray array when keyValue instanceof JsSymbol -> exoticHasMember(array, keyValue);
            case JsArray array -> arrayHasMember(array, JsCoercion.toStr(keyValue)) || exoticHasMember(array, keyValue);
            case JsArguments arguments -> exoticHasMember(arguments, keyValue);
            case JsTypedArray typed when typed.hasCanonicalNumericIndex(keyValue) -> typed.hasOwnKey(keyValue);
            case JsTypedArray typed -> indexInRange(keyValue, typed.length()) || exoticHasMember(typed, keyValue);
            case JsCallableProperties callable when keyValue instanceof JsSymbol ->
                exoticHasMember((JsValue) callable, keyValue);
            case JsCallableProperties callable -> callableHasMember(callable, JsCoercion.toStr(keyValue));
            default -> {
                if (!isObjectLike(container)) {
                    throw new TypeErrorException(
                            "Cannot use 'in' operator to search for '" + JsCoercion.toStr(keyValue) + "'");
                }
                yield exoticHasMember(container, keyValue);
            }
        };
    }

    public boolean indexInRange(JsValue keyValue, int length) {
        if (keyValue instanceof JsSymbol) {
            return false;
        }
        final var key = JsCoercion.toStr(keyValue);
        final var index = arrayIndex(key);
        return index != null && index < length;
    }

    public boolean exoticHasMember(JsValue container, JsValue keyValue) {
        final var table = container.ownProperties();
        if (table != null) {
            if (keyValue instanceof JsSymbol symbol && (table.hasSymbol(symbol) || table.hasSymbolAccessor(symbol))) {
                return true;
            }
            if (!(keyValue instanceof JsSymbol)) {
                final var key = JsCoercion.toStr(keyValue);
                if (table.has(key) || table.hasAccessor(key)) {
                    return true;
                }
            }
        }
        final var chainStart = container.getProto() == null
                ? interp.intrinsics.protoFor(container)
                : container.getProto();
        if (keyValue instanceof JsSymbol symbol) {
            return interp.members.chainHasSymbol(chainStart, symbol);
        }
        return interp.members.chainHasKey(chainStart, JsCoercion.toStr(keyValue));
    }

    public boolean callableHasMember(JsCallableProperties callable, String key) {
        if (callable.hasProperty(key) || FunctionProtoBuiltins.metadata((JsValue) callable, key) != null) {
            return true;
        }
        if (callable instanceof JsFunction function && "prototype".equals(key) && !function.isArrow()
                && !function.isMethod() && !(function.isAsync() && !function.isGenerator())) {
            return true;
        }
        return interp.members.chainHasKey(interp.intrinsics.protoFor((JsValue) callable), key);
    }

    public boolean hasStaticMember(JsClass cls, String key) {
        if ("prototype".equals(key) || "name".equals(key)) {
            return true;
        }
        if (cls.findStaticGetter(key) != null || cls.findStaticSetter(key) != null
                || cls.findStaticMethod(key) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStaticSymbolMember(JsClass cls, JsSymbol symbol) {
        if (cls.findStaticSymbolGetter(symbol) != null || cls.findStaticSymbolSetter(symbol) != null
                || cls.findStaticSymbolMethod(symbol) != null) {
            return true;
        }
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticSymbolProp(symbol)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStringMember(JsObject object, String key) {
        return interp.members.chainHasKey(object, key);
    }

    public boolean hasSymbolMember(JsObject object, JsSymbol symbol) {
        return interp.members.chainHasSymbol(object, symbol);
    }

}
