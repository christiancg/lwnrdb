package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.ClassBody;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class ClassMemberInstaller {
    private final Interpreter interp;

    ClassMemberInstaller(Interpreter interp) {
        this.interp = interp;
    }

    JsValue fieldKey(FieldDefinition field, Environment classScope) {
        if (!field.isComputed()) {
            return null;
        }
        final var key = JsCoercion.toPropertyKey(interp.eval(field.getKey(), classScope), interp.ops());
        if (field.isStatic() && isNamed(key)) {
            throw new TypeErrorException("Classes may not have a static field named 'prototype'");
        }
        return key;
    }

    static boolean isNamed(JsValue key) {
        return key instanceof JsString string && "prototype".equals(string.getValue());
    }

    static void declarePrivateNames(JsClass cls, ClassBody body) {
        for (final var member : body.getMembers()) {
            final var key = switch (member) {
                case MethodDefinition method -> method.getKey();
                case FieldDefinition field -> field.getKey();
                default -> null;
            };
            if (key instanceof PrivateIdentifier priv) {
                cls.declarePrivateName(priv.getName());
            }
        }
    }

    void installMethod(JsClass cls, MethodDefinition method, Environment classScope) {
        final var value = method.getValue();
        final var fn = interp.makeFunction(null, value.getParams(), value.getBody(), false, false, value.isAsync(),
                value.isGenerator(), cls.getMethodScope(), value.getSourceText());
        final var kind = method.getKind();
        if ("constructor".equals(kind)) {
            if (cls.isDerived()) {
                fn.markDerivedConstructor();
            }
            cls.setConstructor(fn);
            return;
        }
        if (!fn.isGenerator()) {
            fn.markMethod();
        }
        if (method.getKey() instanceof PrivateIdentifier priv) {
            final var privateName = cls.declarePrivateName(priv.getName());
            fn.setInferredName(accessorName(kind, "#" + priv.getName()));
            if (method.isStatic()) {
                cls.addPrivateStaticMethod(privateName, kind, fn);
            } else {
                cls.addPrivateInstanceMethod(privateName, kind, fn);
            }
            return;
        }
        if (method.isComputed()) {
            final var keyValue = JsCoercion.toPropertyKey(interp.eval(method.getKey(), classScope), interp.ops());
            if (method.isStatic() && isNamed(keyValue)) {
                throw new TypeErrorException("Classes may not have a static method named 'prototype'");
            }
            if (keyValue instanceof JsSymbol symbol) {
                fn.setInferredName(accessorName(kind, symbolMethodName(symbol)));
                if (method.isStatic()) {
                    cls.addStaticSymbolMethod(symbol, kind, fn);
                    publishSymbolMember(cls.getStaticOwner(), symbol, kind, fn);
                } else {
                    cls.addInstanceSymbolMethod(symbol, kind, fn);
                    publishSymbolMember(cls.getPrototype(), symbol, kind, fn);
                }
                return;
            }
            installStringMethod(cls, method, kind, fn, JsCoercion.toStr(keyValue));
            return;
        }
        installStringMethod(cls, method, kind, fn, staticKeyName(method.getKey()));
    }

    static String accessorName(String kind, String key) {
        return "get".equals(kind) || "set".equals(kind) ? kind + " " + key : key;
    }

    static void publishSymbolMember(JsObject owner, JsSymbol symbol, String kind, JsFunction fn) {
        if ("get".equals(kind)) {
            owner.defineSymbolAccessor(symbol, fn, null);
        } else if ("set".equals(kind)) {
            owner.defineSymbolAccessor(symbol, null, fn);
        } else {
            owner.setSymbol(symbol, fn);
        }
        owner.setSymbolFlags(symbol, JsObject.PropertyFlags.HIDDEN);
    }

    static void nameField(FieldDefinition field, JsValue value, String key) {
        InterpreterUtils.applyInferredName(field.getValue(), value, key);
    }

    static String symbolMethodName(JsSymbol symbol) {
        final var description = symbol.getDescription();
        return description == null ? "" : "[" + description + "]";
    }

    void installStringMethod(JsClass cls, MethodDefinition method, String kind, JsFunction fn, String key) {
        fn.setInferredName(accessorName(kind, key));
        if (method.isStatic()) {
            cls.addStaticMethod(key, kind, fn);
        } else {
            cls.addInstanceMethod(key, kind, fn);
        }
    }

    void runStaticInit(JsClass cls, List<StaticEntry> staticInit) {
        final var staticScope = cls.getMethodScope().child();
        staticScope.defineThis(cls);
        for (final var entry : staticInit) {
            if (entry.field() != null) {
                runStaticField(cls, entry, staticScope);
            } else {
                final var blockEnv = staticScope.functionChild();
                interp.hoist(entry.block().getBody(), blockEnv);
                for (final var statement : entry.block().getBody()) {
                    if (!interp.evalStatement(statement, blockEnv).isNormal()) {
                        break;
                    }
                }
            }
        }
    }

    void runStaticField(JsClass cls, StaticEntry entry, Environment staticScope) {
        final var field = entry.field();
        final var value = field.getValue() == null
                ? JsUndefined.getInstance()
                : interp.eval(field.getValue(), staticScope);
        if (field.getKey() instanceof PrivateIdentifier priv) {
            nameField(field, value, "#" + priv.getName());
            if (!cls.addPrivateStaticField(cls.declarePrivateName(priv.getName()), value)) {
                throw new TypeErrorException("Cannot add private field #" + priv.getName() + " to this class object");
            }
            return;
        }
        if (entry.key() instanceof JsSymbol symbol) {
            nameField(field, value, symbolMethodName(symbol));
            cls.setStaticSymbolProp(symbol, value);
            return;
        }
        final var key = entry.key() == null ? staticKeyName(field.getKey()) : ((JsString) entry.key()).getValue();
        nameField(field, value, key);
        cls.defineStaticField(key, value);
    }
}
