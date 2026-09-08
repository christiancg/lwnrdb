package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isObjectLike;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.staticKeyName;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.nodes.PrivateIdentifier;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

final class ClassConstruction {
    private final Interpreter interp;
    private final ClassEvaluator classEvaluator;

    ClassConstruction(Interpreter interp, ClassEvaluator classEvaluator) {
        this.interp = interp;
        this.classEvaluator = classEvaluator;
    }

    JsValue construct(JsClass cls, List<JsValue> args, JsValue newTarget) {
        final var instance = new JsObject();
        instance.setKlass(cls);
        final var declared = interp.getMemberByKey(newTarget, new JsString("prototype"));
        instance.setProto(isObjectLike(declared) ? declared : cls.getPrototype());
        return callConstructorChain(cls, instance, args, newTarget);
    }

    JsValue callConstructorChain(JsClass cls, JsObject instance, List<JsValue> args, JsValue newTarget) {
        final var constructor = cls.getConstructor();
        final var superCtor = cls.getSuperClass() == null ? cls.getSuperConstructor() : null;
        if (constructor != null && cls.isDerived()) {
            return overrideOrInstance(interp.callFunction(constructor, instance, args, newTarget), instance);
        }
        var self = (JsValue) instance;
        if (superCtor != null) {
            self = applySuperConstructor(superCtor, instance, args, newTarget);
        } else if (cls.hasNullHeritage()) {
            throw new TypeErrorException("Super constructor null of " + cls.getName() + " is not a constructor");
        } else if (cls.getSuperClass() != null) {
            self = callConstructorChain(cls.getSuperClass(), instance, args, newTarget);
        }
        final var fieldTarget = unwrapForFieldInit(self);
        if (fieldTarget != null) {
            initFields(cls, self, fieldTarget);
        }
        if (constructor != null) {
            return overrideOrInstance(interp.callFunction(constructor, self, args, newTarget), self);
        }
        return self;
    }

    static JsValue overrideOrInstance(JsValue returned, JsValue instance) {
        return isObjectLike(returned) ? returned : instance;
    }

    JsObject unwrapForFieldInit(JsValue self) {
        final var layers = new ArrayList<JsValue>();
        var current = self;
        while (current instanceof JsProxy proxy) {
            layers.add(current);
            current = proxy.getTarget();
        }
        if (!(current instanceof JsObject object)) {
            return null;
        }
        for (final var layer : layers) {
            classEvaluator.bridgePrivateStorage(layer, object);
        }
        return object;
    }

    JsValue applySuperConstructor(JsValue superCtor, JsObject instance, List<JsValue> args, JsValue newTarget) {
        return switch (superCtor) {
            case JsNativeFunction nativeSuper -> {
                applyNativeSuper(nativeSuper, instance, args);
                yield instance;
            }
            case JsFunction function ->
                overrideOrInstance(interp.callFunction(function, instance, args, newTarget), instance);
            default -> {
                adoptConstructed(interp.construct(superCtor, args, newTarget), instance);
                yield instance;
            }
        };
    }

    void applyNativeSuper(JsNativeFunction nativeSuper, JsObject instance, List<JsValue> args) {
        adoptConstructed(nativeSuper.invoke(instance, args), instance);
    }

    static void adoptConstructed(JsValue produced, JsObject instance) {
        if (produced instanceof JsObject object) {
            for (final var key : object.keys()) {
                instance.defineValue(key, object.get(key));
                instance.setFlags(key, object.getFlags(key));
            }
            if (object.isErrorData()) {
                instance.markErrorData();
            }
        } else {
            instance.setPrimitive(produced);
        }
    }

    void initFields(JsClass cls, JsValue self, JsObject storage) {
        if (cls.hasPrivateInstanceBrand() && !storage.isExtensible()) {
            throw new TypeErrorException(
                    "Cannot add private members to a non-extensible object (" + cls.getName() + ")");
        }
        if (!storage.addPrivateBrand(cls) && cls.hasPrivateInstanceBrand()) {
            throw new TypeErrorException(
                    "Cannot initialize the private members of " + cls.getName() + " twice on the same object");
        }
        for (final var entry : cls.getInstanceFields()) {
            final var field = entry.definition();
            final var fieldScope = cls.getMethodScope().child();
            fieldScope.defineThis(self);
            final var value = field.getValue() == null
                    ? JsUndefined.getInstance()
                    : interp.eval(field.getValue(), fieldScope);
            if (field.getKey() instanceof PrivateIdentifier priv) {
                ClassMemberInstaller.nameField(field, value, "#" + priv.getName());
                if (!storage.addPrivate(cls.declarePrivateName(priv.getName()), value)) {
                    throw new TypeErrorException("Cannot add private field #" + priv.getName() + " to this object");
                }
            } else if (entry.key() instanceof JsSymbol symbol) {
                ClassMemberInstaller.nameField(field, value, ClassMemberInstaller.symbolMethodName(symbol));
                defineFieldProperty(self, symbol, value);
            } else {
                final var key = entry.key() == null
                        ? staticKeyName(field.getKey())
                        : ((JsString) entry.key()).getValue();
                ClassMemberInstaller.nameField(field, value, key);
                defineFieldProperty(self, new JsString(key), value);
            }
        }
    }

    void defineFieldProperty(JsValue self, JsValue key, JsValue value) {
        final var descriptor = new JsObject();
        descriptor.setProto(interp.intrinsics().objectProto);
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.TRUE);
        descriptor.set("enumerable", JsBoolean.TRUE);
        descriptor.set("configurable", JsBoolean.TRUE);
        if (!interp.ops().defineProperty(self, key, descriptor)) {
            throw new TypeErrorException(
                    "Cannot define field '" + JsCoercion.toStr(key, interp.ops()) + "' on this object");
        }
    }
}
