package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.List;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.OrdinarySet;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;
import org.techhouse.simplejs.values.PropertyTable;

final class MemberWriter {
    private final Interpreter interp;
    private final ProxyDispatch proxies;
    private final MemberEvaluator members;

    MemberWriter(Interpreter interp, ProxyDispatch proxies, MemberEvaluator members) {
        this.interp = interp;
        this.proxies = proxies;
        this.members = members;
    }

    boolean setArgumentsMember(JsArguments arguments, String key, JsValue value) {
        final var descriptor = arguments.getOwnProperty(new JsString(key));
        if (descriptor != null && descriptor.isAccessorDescriptor()) {
            if (!isCallable(descriptor.setter())) {
                return false;
            }
            interp.callValue(descriptor.setter(), arguments, List.of(value));
            return true;
        }
        return arguments.setProperty(key, value);
    }

    public boolean setMember(JsValue target, String key, JsValue value, JsValue receiver) {
        if (target instanceof JsTypedArray typed && typed.hasCanonicalNumericIndex(new JsString(key))) {
            return typed.setExoticIndex(new JsString(key), value, receiver) || setOnReceiver(receiver, key, value);
        }
        if (target instanceof JsObject object) {
            return setObjectMember(object, key, value, receiver);
        }
        if (receiver != target) {
            return OrdinarySet.set(interp.ops(), target, new JsString(key), value, receiver);
        }
        return setMember(target, key, value);
    }

    boolean setObjectMember(JsObject object, String key, JsValue value, JsValue receiver) {
        if (!object.has(key) && "length".equals(key) && receiver == object
                && object.getPrimitive() instanceof JsArray primitiveArray) {
            return primitiveArray.setLength(requireLength(value, interp.ops()));
        }
        if (!object.has(key) && object.getPrimitive() instanceof JsTypedArray primitiveTyped
                && primitiveTyped.hasCanonicalNumericIndex(new JsString(key))) {
            return receiver == object
                    ? primitiveTyped.setExoticIndex(new JsString(key), value, primitiveTyped)
                    : setOnReceiver(receiver, key, value);
        }
        if (protoOwnsNonWritableData(object, key)) {
            return false;
        }
        if (!object.has(key)) {
            for (var chain = members.newChain(object); chain.hasLink(); chain.advance()) {
                if (chain.link() instanceof JsProxy proxy) {
                    return proxies.set(proxy, new JsString(key), value, receiver);
                }
                if (chain.link() instanceof JsTypedArray typed && typed.hasCanonicalNumericIndex(new JsString(key))) {
                    return typed.setExoticIndex(new JsString(key), value, receiver)
                            || setOnReceiver(receiver, key, value);
                }
                final var accessor = MemberEvaluator.protoAccessor(chain.link(), key);
                if (accessor != null) {
                    if (!isCallable(accessor.setter())) {
                        return false;
                    }
                    interp.callValue(accessor.setter(), receiver, List.of(value));
                    return true;
                }
                if (protoOwnsNonWritableData(chain.link(), key)) {
                    return false;
                }
            }
        }
        if (receiver == object && object == interp.intrinsics().arrayProto) {
            final var index = InterpreterUtils.arrayIndex(key);
            if (index != null) {
                final var wrote = object.set(key, value);
                if (wrote) {
                    growArrayPrototypeLength(object, index);
                }
                return wrote;
            }
        }
        return receiver == object ? object.set(key, value) : setOnReceiver(receiver, key, value);
    }

    void growArrayPrototypeLength(JsObject arrayProto, int index) {
        final var currentLength = (int) JsCoercion.toNumber(arrayProto.get("length"));
        if (index + 1 > currentLength) {
            arrayProto.set("length", new JsNumber(index + 1));
        }
    }

    boolean setOnReceiver(JsValue receiver, String key, JsValue value) {
        if (receiver instanceof JsProxy) {
            return setOnProxyReceiver(receiver, key, value);
        }
        final var keyValue = new JsString(key);
        final var existing = receiver.getOwnProperty(keyValue);
        if (existing != null && (existing.isAccessorDescriptor() || !existing.writableOr(false))) {
            return false;
        }
        return receiver.defineOwnProperty(keyValue,
                existing == null
                        ? PropertyDescriptor.data(value, JsObject.PropertyFlags.DEFAULT)
                        : new PropertyDescriptor(value, null, null, null, null, null));
    }

    boolean setOnProxyReceiver(JsValue receiver, String key, JsValue value) {
        final var keyValue = new JsString(key);
        final var existing = interp.ops().getOwnPropertyDescriptor(receiver, keyValue);
        if (!(existing instanceof JsUndefined)) {
            final var isAccessor = interp.hasMember(existing, new JsString("get"))
                    || interp.hasMember(existing, new JsString("set"));
            final var writable = interp.hasMember(existing, new JsString("writable"))
                    && JsCoercion.toBoolean(interp.getMember(existing, "writable"));
            if (isAccessor || !writable) {
                return false;
            }
            final var valueOnly = new JsObject();
            valueOnly.set("value", value);
            return interp.ops().defineProperty(receiver, keyValue, valueOnly);
        }
        final var fullDescriptor = new JsObject();
        fullDescriptor.set("value", value);
        fullDescriptor.set("writable", JsBoolean.TRUE);
        fullDescriptor.set("enumerable", JsBoolean.TRUE);
        fullDescriptor.set("configurable", JsBoolean.TRUE);
        return interp.ops().defineProperty(receiver, keyValue, fullDescriptor);
    }

    public boolean setMember(JsValue target, String key, JsValue value) {
        return switch (target) {
            case JsProxy proxy -> proxies.set(proxy, new JsString(key), value);
            case JsGlobalObject global -> {
                global.getEnv().setGlobal(key, value);
                yield true;
            }
            case JsArguments arguments -> setArgumentsMember(arguments, key, value);
            case JsObject object -> setObjectMember(object, key, value, object);
            case JsClass cls -> {
                final var setter = cls.findStaticSetter(key);
                if (setter != null) {
                    interp.callFunction(setter, cls, List.of(value));
                } else if (hasStaticPropInHierarchy(cls, key)) {
                    cls.setStaticProp(key, value);
                } else {
                    final var outcome = setThroughChain(cls, key, cls, value);
                    if (outcome.handled()) {
                        yield outcome.result();
                    }
                    cls.setStaticProp(key, value);
                }
                yield true;
            }
            case JsArray array -> setArrayMember(array, key, value);
            case JsTypedArray typed -> typed.setExoticIndex(new JsString(key), value, typed)
                    || setTableMember(typed, typed.ownProperties(), key, value);
            case JsPromise promise -> promise.ownProperties().set(key, value);
            case JsRegExp regexp -> (regexp.ownProperties().has(key) || !RegexBuiltins.isSetterless(key))
                    && regexp.ownProperties().set(key, value);
            case JsNull ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsUndefined ignored -> throw new TypeErrorException(
                    "Cannot set properties of " + JsCoercion.toStr(target) + " (setting '" + key + "')");
            case JsCallableProperties callable -> {
                if (isNonWritableMetadata(callable, key)) {
                    yield false;
                }
                if ("prototype".equals(key)) {
                    if (callable instanceof JsFunction fn) {
                        fn.setPrototype(value);
                    } else if (callable instanceof JsNativeFunction nf && InterpreterUtils.isObjectLike(value)) {
                        nf.setPrototype(value);
                    }
                    yield true;
                }
                if (!callable.hasProperty(key)) {
                    final var outcome = setThroughChain(target, key, target, value);
                    if (outcome.handled()) {
                        yield outcome.result();
                    }
                } else if (protoOwnsNonWritableData(target, key)) {
                    yield false;
                }
                callable.setEnumerableProperty(key, value);
                yield true;
            }
            default -> {
                final var table = target.ownProperties();
                yield table == null || setTableMember(target, table, key, value);
            }
        };
    }

    static boolean hasStaticPropInHierarchy(JsClass cls, String key) {
        for (var current = cls; current != null; current = current.getSuperClass()) {
            if (current.hasStaticProp(key)) {
                return true;
            }
        }
        return false;
    }

    boolean setTableMember(JsValue target, PropertyTable table, String key, JsValue value) {
        if (table.hasAccessor(key)) {
            final var setter = table.getAccessorSetter(key);
            if (setter == null) {
                return false;
            }
            interp.callValue(setter, target, List.of(value));
            return true;
        }
        if (!table.has(key)) {
            final var outcome = setThroughChain(target, key, target, value);
            if (outcome.handled()) {
                return outcome.result();
            }
        }
        return table.set(key, value);
    }

    record ChainSetOutcome(boolean handled, boolean result) {
        private static final ChainSetOutcome NOT_HANDLED = new ChainSetOutcome(false, false);

        private static ChainSetOutcome of(boolean result) {
            return new ChainSetOutcome(true, result);
        }
    }

    ChainSetOutcome setThroughChain(JsValue target, String key, JsValue receiver, JsValue value) {
        for (var chain = members.newChain(members.protoChainStart(target)); chain.hasLink(); chain.advance()) {
            if (chain.link() instanceof JsProxy proxy) {
                return ChainSetOutcome.of(proxies.set(proxy, new JsString(key), value, receiver));
            }
            if (chain.link() instanceof JsTypedArray typed && typed.hasCanonicalNumericIndex(new JsString(key))) {
                return ChainSetOutcome.of(typed.setExoticIndex(new JsString(key), value, receiver)
                        || setOnReceiver(receiver, key, value));
            }
            final var accessor = MemberEvaluator.protoAccessor(chain.link(), key);
            if (accessor != null) {
                return ChainSetOutcome.of(writeThroughAccessor(receiver, accessor, value));
            }
            if (protoOwnsNonWritableData(chain.link(), key)) {
                return ChainSetOutcome.of(false);
            }
        }
        return ChainSetOutcome.NOT_HANDLED;
    }

    static boolean protoOwnsNonWritableData(JsValue proto, String key) {
        final var descriptor = proto.getOwnProperty(new JsString(key));
        return descriptor != null && !descriptor.isAccessorDescriptor() && !descriptor.writableOr(true);
    }

    boolean writeThroughAccessor(JsValue target, PropertyDescriptor accessor, JsValue value) {
        if (!isCallable(accessor.setter())) {
            return false;
        }
        interp.callValue(accessor.setter(), target, List.of(value));
        return true;
    }

    static boolean isNonWritableMetadata(JsCallableProperties callable, String key) {
        return ("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)
                && !callable.isMetadataDeleted(key);
    }

    static String writeRejectionMessage(JsValue target, JsValue key) {
        final var name = JsCoercion.toStr(key);
        if (target instanceof JsObject object) {
            if (object.hasAccessor(name) && object.getAccessorSetter(name) == null) {
                return "Cannot set property " + name + " of #<Object> which has only a getter";
            }
            if (object.has(name)) {
                return "Cannot assign to read only property '" + name + "' of object";
            }
        }
        if (target instanceof JsCallableProperties callable && isNonWritableMetadata(callable, name)) {
            return "Cannot assign to read only property '" + name + "' of object";
        }
        if (target instanceof JsArray array) {
            final var index = arrayIndex(name);
            if ((array.hasPropAccessor(name) && !isCallable(array.getPropAccessorSetter(name))) || (index != null
                    && array.hasIndexAccessor(index) && !isCallable(array.getIndexAccessorSetter(index)))) {
                return "Cannot set property " + name + " of #<Array> which has only a getter";
            }
            if (array.isFrozen()) {
                return "Cannot assign to read only property '" + name + "' of object";
            }
        }
        return "Cannot add property " + name + ", object is not extensible";
    }

    boolean setArrayMember(JsArray array, String key, JsValue value) {
        if ("length".equals(key)) {
            return array.setLength(requireLength(value, interp.ops()));
        }
        if (array.hasPropAccessor(key)) {
            final var setter = array.getPropAccessorSetter(key);
            if (!isCallable(setter)) {
                return false;
            }
            interp.callValue(setter, array, List.of(value));
            return true;
        }
        final var index = arrayIndex(key);
        if (index != null && array.hasIndexAccessor(index)) {
            final var setter = array.getIndexAccessorSetter(index);
            if (!isCallable(setter)) {
                return false;
            }
            interp.callValue(setter, array, List.of(value));
            return true;
        }
        final var wideIndex = index == null ? InterpreterUtils.canonicalArrayIndexWide(key) : null;
        if (!arrayOwnsKey(array, key, index)) {
            final var outcome = setThroughChain(array, key, array, value);
            if (outcome.handled()) {
                return outcome.result();
            }
        }
        if (index != null) {
            return array.set(index, value);
        }
        return wideIndex != null ? array.setWideIndex(wideIndex, value) : array.setProperty(key, value);
    }

    static boolean arrayOwnsKey(JsArray array, String key, Integer index) {
        return index == null ? array.hasProperty(key) : index < array.length() && !array.isHole(index);
    }

    long requireLength(JsValue value, InterpreterOps ops) {
        final var newLen = NumberFormatter.toUint32(JsCoercion.toNumber(value, ops));
        final var numberLen = JsCoercion.toNumber(value, ops);
        if (newLen != numberLen) {
            throw new RangeErrorException("Invalid array length");
        }
        return newLen;
    }

}
