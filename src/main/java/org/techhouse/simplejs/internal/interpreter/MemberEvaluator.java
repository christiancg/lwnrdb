package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.cannotReadProperties;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.orUndefined;

import java.util.List;
import org.techhouse.simplejs.builtins.AsyncIteratorBuiltins;
import org.techhouse.simplejs.builtins.IteratorBuiltins;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsDbDateTime;
import org.techhouse.simplejs.values.JsDbTime;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGeo;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTemporalDuration;
import org.techhouse.simplejs.values.JsTemporalInstant;
import org.techhouse.simplejs.values.JsTemporalPlainDate;
import org.techhouse.simplejs.values.JsTemporalPlainDateTime;
import org.techhouse.simplejs.values.JsTemporalPlainMonthDay;
import org.techhouse.simplejs.values.JsTemporalPlainTime;
import org.techhouse.simplejs.values.JsTemporalPlainYearMonth;
import org.techhouse.simplejs.values.JsTemporalZonedDateTime;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.JsVector;
import org.techhouse.simplejs.values.PropertyDescriptor;

public final class MemberEvaluator {
    private final Interpreter interp;
    private final EventLoop eventLoop;
    private final ProxyDispatch proxies;
    private final AsyncGeneratorDriver asyncGenerators;
    private final BuiltinMemberLookup builtinMembers;
    private final MemberWriter writer;

    public enum AsyncStep {
        NEXT, RETURN, THROW
    }

    public MemberEvaluator(Interpreter interp, EventLoop eventLoop, ProxyDispatch proxies) {
        this.interp = interp;
        this.eventLoop = eventLoop;
        this.proxies = proxies;
        this.builtinMembers = new BuiltinMemberLookup(interp);
        this.asyncGenerators = new AsyncGeneratorDriver(interp, eventLoop, builtinMembers);
        this.writer = new MemberWriter(interp, proxies, this);
        this.builtinMembers.bind(this);
    }

    public boolean setMember(JsValue target, String key, JsValue value, JsValue receiver) {
        return writer.setMember(target, key, value, receiver);
    }

    public boolean setMember(JsValue target, String key, JsValue value) {
        return writer.setMember(target, key, value);
    }

    public JsValue driveAsyncGenerator(JsAsyncGenerator generator, AsyncStep kind, JsValue arg) {
        return asyncGenerators.driveAsyncGenerator(generator, kind, arg);
    }

    public void observeAsyncGenerator(JsAsyncGenerator generator, RuntimeException escaped) {
        asyncGenerators.observeAsyncGenerator(generator, escaped);
    }

    public static String writeRejectionMessage(JsValue target, JsValue key) {
        return MemberWriter.writeRejectionMessage(target, key);
    }

    Chain newChain(JsValue start) {
        return new Chain(start);
    }

    public JsValue getSymbolMember(JsValue target, JsSymbol symbol) {
        return switch (target) {
            case JsArguments ignored when symbol == JsSymbol.ITERATOR ->
                interp.intrinsics().arrayProto.getSymbol(symbol);
            case JsObject object -> objectSymbolMember(object, symbol);
            case JsClass cls -> classSymbolMember(cls, symbol);
            default -> intrinsicSymbolMember(target, symbol);
        };
    }

    private JsValue intrinsicSymbolMember(JsValue target, JsSymbol symbol) {
        final var own = target.getOwnProperty(symbol);
        if (own != null) {
            return fromDescriptor(own, target);
        }
        return orUndefined(chainSymbolMember(protoChainStart(target), symbol, target));
    }

    public final class Chain {
        private JsValue link;
        private boolean synthesised;

        Chain(JsValue start) {
            link = start;
        }

        boolean hasLink() {
            return link != null;
        }

        JsValue link() {
            return link;
        }

        void advance() {
            final var next = link.getProto();
            if (next == null && !synthesised && !(link instanceof JsObject)) {
                link = interp.intrinsics().protoFor(link);
                synthesised = true;
                return;
            }
            link = next;
        }
    }

    public JsValue chainMember(JsValue start, String key, JsValue receiver) {
        for (var chain = new Chain(start); chain.hasLink(); chain.advance()) {
            final var found = protoMember(chain.link(), key, receiver);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private JsValue chainSymbolMember(JsValue start, JsSymbol symbol, JsValue receiver) {
        for (var chain = new Chain(start); chain.hasLink(); chain.advance()) {
            final var found = protoSymbolMember(chain.link(), symbol, receiver);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public boolean chainHasKey(JsValue start, String key) {
        for (var chain = new Chain(start); chain.hasLink(); chain.advance()) {
            if (chain.link() instanceof JsProxy proxy) {
                return proxies.has(proxy, new JsString(key));
            }
            if (InterpreterUtils.protoOwnsKey(chain.link(), key)) {
                return true;
            }
        }
        return false;
    }

    public boolean chainHasSymbol(JsValue start, JsSymbol symbol) {
        for (var chain = new Chain(start); chain.hasLink(); chain.advance()) {
            if (chain.link() instanceof JsProxy proxy) {
                return proxies.has(proxy, symbol);
            }
            if (InterpreterUtils.protoOwnsSymbol(chain.link(), symbol)) {
                return true;
            }
        }
        return false;
    }

    JsValue protoMember(JsValue proto, String key, JsValue receiver) {
        if (proto instanceof JsProxy proxy) {
            return proxies.get(proxy, new JsString(key), receiver);
        }
        if (proto instanceof JsObject object) {
            final var getter = object.getAccessorGetter(key);
            if (getter != null) {
                return interp.callValue(getter, receiver, List.of());
            }
            return object.has(key) ? object.get(key) : null;
        }
        return fromDescriptor(proto.getOwnProperty(new JsString(key)), receiver);
    }

    private JsValue protoSymbolMember(JsValue proto, JsSymbol symbol, JsValue receiver) {
        if (proto instanceof JsProxy proxy) {
            return proxies.get(proxy, symbol, receiver);
        }
        if (proto instanceof JsObject object) {
            if (object.hasSymbolAccessor(symbol)) {
                final var getter = object.getSymbolAccessorGetter(symbol);
                return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, receiver, List.of());
            }
            return object.hasSymbol(symbol) ? object.getSymbol(symbol) : null;
        }
        return fromDescriptor(proto.getOwnProperty(symbol), receiver);
    }

    private JsValue fromDescriptor(PropertyDescriptor descriptor, JsValue receiver) {
        if (descriptor == null) {
            return null;
        }
        if (!descriptor.isAccessorDescriptor()) {
            return descriptor.value();
        }
        return isCallable(descriptor.getter())
                ? interp.callValue(descriptor.getter(), receiver, List.of())
                : JsUndefined.getInstance();
    }

    public static PropertyDescriptor protoAccessor(JsValue proto, String key) {
        if (proto instanceof JsObject object) {
            return object.hasAccessor(key)
                    ? PropertyDescriptor.accessor(object.getAccessorGetter(key), object.getAccessorSetter(key),
                            JsObject.PropertyFlags.DEFAULT)
                    : null;
        }
        final var descriptor = proto.getOwnProperty(new JsString(key));
        return descriptor != null && descriptor.isAccessorDescriptor() ? descriptor : null;
    }

    private JsValue objectSymbolMember(JsObject object, JsSymbol symbol) {
        if (object.hasSymbolAccessor(symbol)) {
            final var getter = object.getSymbolAccessorGetter(symbol);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, object, List.of());
        }
        if (object.hasSymbol(symbol)) {
            return object.getSymbol(symbol);
        }
        final var cls = object.getKlass();
        if (cls != null) {
            final var getter = cls.findInstanceSymbolGetter(symbol);
            if (getter != null) {
                return interp.callFunction(getter, object, List.of());
            }
            final var method = cls.findInstanceSymbolMethod(symbol);
            if (method != null) {
                return method;
            }
        }
        final var inherited = chainSymbolMember(object.getProto(), symbol, object);
        return inherited == null ? object.getSymbol(symbol) : inherited;
    }

    private JsValue classSymbolMember(JsClass cls, JsSymbol symbol) {
        final var getter = cls.findStaticSymbolGetter(symbol);
        if (getter != null) {
            return interp.callFunction(getter, cls, List.of());
        }
        final var method = cls.findStaticSymbolMethod(symbol);
        if (method != null) {
            return method;
        }
        if (cls.hasStaticSymbolProp(symbol)) {
            return cls.getStaticSymbolProp(symbol);
        }
        return JsUndefined.getInstance();
    }

    public JsValue getMember(JsValue target, String key, JsValue receiver) {
        if (target instanceof JsObject object) {
            return getObjectMember(object, key, receiver);
        }
        return getMember(target, key);
    }

    public JsValue getMember(JsValue target, String key) {
        return switch (target) {
            case JsProxy proxy -> proxies.get(proxy, new JsString(key));
            case JsGlobalObject global -> getGlobalMember(global, key);
            case JsArguments arguments -> getArgumentsMember(arguments, key);
            case JsObject object -> getObjectMember(object, key);
            case JsClass cls -> interp.getStaticMember(cls, key);
            case JsArray array -> getArrayMember(array, key);
            case JsString string -> builtinMembers.getStringMember(string, key);
            case JsNumber number -> builtinMembers.numberMember(number, key);
            case JsSymbol symbol -> builtinMembers.symbolMember(symbol, key);
            case JsGenerator generator -> builtinMembers.generatorMethod(generator, key);
            case JsAsyncGenerator generator -> asyncGenerators.asyncGeneratorMethod(generator, key);
            case JsRegExp regexp -> builtinMembers.regExpMember(regexp, key);
            case JsMap map -> builtinMembers.mapMember(map, key);
            case JsSet set -> builtinMembers.jsSetMember(set, key);
            case JsDate date -> builtinMembers.dateMember(date, key);
            case JsTemporalDuration duration -> builtinMembers.intrinsicMember(duration, key);
            case JsTemporalPlainTime time -> builtinMembers.intrinsicMember(time, key);
            case JsTemporalPlainDate date -> builtinMembers.intrinsicMember(date, key);
            case JsTemporalInstant instant -> builtinMembers.intrinsicMember(instant, key);
            case JsTemporalPlainYearMonth yearMonth -> builtinMembers.intrinsicMember(yearMonth, key);
            case JsTemporalPlainMonthDay monthDay -> builtinMembers.intrinsicMember(monthDay, key);
            case JsTemporalPlainDateTime dt -> builtinMembers.intrinsicMember(dt, key);
            case JsTemporalZonedDateTime zdt -> builtinMembers.intrinsicMember(zdt, key);
            case JsGeo geo -> builtinMembers.intrinsicMember(geo, key);
            case JsVector vector -> builtinMembers.intrinsicMember(vector, key);
            case JsDbDateTime dbDateTime -> builtinMembers.intrinsicMember(dbDateTime, key);
            case JsDbTime dbTime -> builtinMembers.intrinsicMember(dbTime, key);
            case JsTypedArray typed -> builtinMembers.typedArrayMember(typed, key);
            case JsArrayBuffer buffer -> builtinMembers.bufferMember(buffer, key);
            case JsDataView view -> builtinMembers.dataViewMember(view, key);
            case JsPromise promise -> builtinMembers.promiseMethod(promise, key);
            case JsBoolean bool -> builtinMembers.intrinsicMember(bool, key);
            case JsBigInt bigInt -> builtinMembers.intrinsicMember(bigInt, key);
            case JsFunction fn -> builtinMembers.functionMember(fn, key);
            case JsNativeFunction nf -> builtinMembers.functionMember(nf, key);
            case JsNull ignored -> throw cannotReadProperties(target, key);
            case JsUndefined ignored -> throw cannotReadProperties(target, key);
            default -> JsUndefined.getInstance();
        };
    }

    private JsValue getObjectMember(JsObject object, String key) {
        return getObjectMember(object, key, object);
    }

    private JsValue getObjectMember(JsObject object, String key, JsValue receiver) {
        if (object.hasAccessor(key)) {
            final var accessorGetter = object.getAccessorGetter(key);
            return accessorGetter != null
                    ? interp.callValue(accessorGetter, receiver, List.of())
                    : JsUndefined.getInstance();
        }
        if (!object.has(key)) {
            if ("length".equals(key) && object.getPrimitive() instanceof JsArray primitiveArray) {
                return new JsNumber(primitiveArray.length());
            }
            final var inherited = chainMember(object.getProto(), key, receiver);
            if (inherited != null) {
                return inherited;
            }
            if (!object.isProtoExplicitlyNull()) {
                final var intrinsic = orUndefined(chainMember(interp.intrinsics().protoFor(object), key, object));
                if (!(intrinsic instanceof JsUndefined)) {
                    return intrinsic;
                }
            }
            if (AsyncIteratorBuiltins.isHelperName(key) && isAsyncIteratorLike(object)) {
                return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
            }
            if (IteratorBuiltins.isHelperName(key) && isIteratorLike(object)) {
                return IteratorBuiltins.helper(interp.ops(), key, interp.intrinsics().objectProto);
            }
            if (object.getPrimitive() != null) {
                return getMember(object.getPrimitive(), key);
            }
        }
        return object.get(key);
    }

    private JsValue getGlobalMember(JsGlobalObject global, String key) {
        final var value = global.getEnv().tryGetGlobalProperty(key);
        if (value != null) {
            return value;
        }
        final var own = fromDescriptor(global.getOwnProperty(new JsString(key)), global);
        if (own != null) {
            return own;
        }
        return orUndefined(chainMember(interp.intrinsics().objectProto, key, global));
    }

    private JsValue getArgumentsMember(JsArguments arguments, String key) {
        final var descriptor = arguments.getOwnProperty(new JsString(key));
        if (descriptor == null) {
            return builtinMembers.intrinsicMember(arguments, key);
        }
        if (!descriptor.isAccessorDescriptor()) {
            return descriptor.value();
        }
        return isCallable(descriptor.getter())
                ? interp.callValue(descriptor.getter(), arguments, List.of())
                : JsUndefined.getInstance();
    }

    private JsValue getArrayMember(JsArray array, String key) {
        if ("length".equals(key)) {
            return new JsNumber(array.length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            if (array.hasIndexAccessor(index)) {
                final var getter = array.getIndexAccessorGetter(index);
                return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, array, List.of());
            }
            if (index < array.length() && !array.isHole(index)) {
                return array.get(index);
            }
        }
        if (array.hasPropAccessor(key)) {
            final var getter = array.getPropAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, array, List.of());
        }
        if (array.hasProperty(key)) {
            return array.getProperty(key);
        }
        return array.getProto() == null
                ? builtinMembers.intrinsicMember(array, key)
                : orUndefined(chainMember(array.getProto(), key, array));
    }

    public JsValue protoChainStart(JsValue target) {
        final var proto = target.getProto();
        return proto == null ? interp.intrinsics().protoFor(target) : proto;
    }

    private boolean isIteratorLike(JsObject object) {
        if (object.has("next")) {
            return isCallable(object.get("next"));
        }
        final var proto = object.getProto();
        if (proto == null) {
            return false;
        }
        final var next = protoMember(proto, "next", object);
        return isCallable(next);
    }

    private boolean isAsyncIteratorLike(JsObject object) {
        return isIteratorLike(object) && object.hasSymbol(JsSymbol.ASYNC_ITERATOR);
    }
}
