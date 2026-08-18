package org.techhouse.simplejs.internal.interpreter;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.arrayIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.cannotReadProperties;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.orUndefined;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.stepResult;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.toErrorValue;

import java.util.List;
import org.techhouse.simplejs.builtins.AsyncIteratorBuiltins;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.builtins.IteratorBuiltins;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.builtins.SymbolBuiltins;
import org.techhouse.simplejs.builtins.TypedArrayBuiltins;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Coroutine;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
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
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.PropertyDescriptor;
import org.techhouse.simplejs.values.PropertyTable;

// Property access dispatch: string- and symbol-keyed member reads/writes across every runtime
// value type, plus the lazily-built method objects for promises, generators and async generators.
// Re-entry into the interpreter (calling getters/setters/methods) and proxy fallbacks route
// through the Interpreter and ProxyDispatch seams; promise/async-generator settlement uses the
// shared EventLoop.
public final class MemberEvaluator {
    private final Interpreter interp;
    private final EventLoop eventLoop;
    private final ProxyDispatch proxies;

    public enum AsyncStep {
        NEXT, RETURN, THROW
    }

    public MemberEvaluator(Interpreter interp, EventLoop eventLoop, ProxyDispatch proxies) {
        this.interp = interp;
        this.eventLoop = eventLoop;
        this.proxies = proxies;
    }

    public JsValue getSymbolMember(JsValue target, JsSymbol symbol) {
        return switch (target) {
            case JsArguments ignored when symbol == JsSymbol.ITERATOR ->
                interp.intrinsics().arrayProto().getSymbol(symbol);
            case JsObject object -> objectSymbolMember(object, symbol);
            case JsClass cls -> classSymbolMember(cls, symbol);
            default -> intrinsicSymbolMember(target, symbol);
        };
    }

    // Fallback for value types with no dedicated case above (JsRegExp, JsDate, JsPromise, numeric/
    // boolean wrappers, ...): an own symbol-keyed property first, then the realm's intrinsic prototype
    // chain, mirroring what intrinsicMember already does for string-keyed lookups.
    private JsValue intrinsicSymbolMember(JsValue target, JsSymbol symbol) {
        final var own = target.getOwnProperty(symbol);
        if (own != null) {
            return fromDescriptor(own, target);
        }
        return orUndefined(chainSymbolMember(interp.intrinsics().protoFor(target), symbol, target));
    }

    // A [[Prototype]] chain walker. A link may be any object-like value; one that is not a plain
    // JsObject has no prototype slot of its own, so the chain continues through the realm's intrinsic
    // prototype for its type (an array links on to Array.prototype). That synthesis is taken at most
    // once per walk: intrinsic prototypes are ordinary objects whose own links are authoritative, and
    // re-entering it on a link reachable from an intrinsic would loop forever.
    private final class Chain {
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

    JsValue chainMember(JsValue start, String key, JsValue receiver) {
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

    // One prototype link's contribution to OrdinaryGet: the value it holds for the key, or null when
    // it owns nothing and the walk continues. A [[Prototype]] may be any object-like value, so only a
    // plain JsObject takes the direct-table fast path; anything else is read through the generic
    // [[GetOwnProperty]] protocol.
    JsValue protoMember(JsValue proto, String key, JsValue receiver) {
        // A proxy link's [[Get]] is authoritative for the rest of the chain, so the trap's answer
        // ends the walk even when it is undefined.
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

    // The accessor a prototype link owns for the key, or null when it owns none (a data property
    // included) and the walk continues.
    private static PropertyDescriptor protoAccessor(JsValue proto, String key) {
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
            case JsString string -> getStringMember(string, key);
            case JsNumber number -> numberMember(number, key);
            case JsSymbol symbol -> symbolMember(symbol, key);
            case JsGenerator generator -> generatorMethod(generator, key);
            case JsAsyncGenerator generator -> asyncGeneratorMethod(generator, key);
            case JsRegExp regexp -> regExpMember(regexp, key);
            case JsMap map -> mapMember(map, key);
            case JsSet set -> jsSetMember(set, key);
            case JsDate date -> dateMember(date, key);
            case JsTypedArray typed -> typedArrayMember(typed, key);
            case JsArrayBuffer buffer -> bufferMember(buffer, key);
            case JsDataView view -> dataViewMember(view, key);
            case JsPromise promise -> promiseMethod(promise, key);
            case JsBoolean bool -> intrinsicMember(bool, key);
            case JsBigInt bigInt -> intrinsicMember(bigInt, key);
            case JsFunction fn -> functionMember(fn, key);
            case JsNativeFunction nf -> functionMember(nf, key);
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
            // An own accessor - even a setter-only one with no getter - is still the own property
            // found for this key, so it terminates the lookup rather than falling through to the
            // prototype chain (mirrors setObjectMember's symmetric `hasAccessor` short-circuit).
            return accessorGetter != null
                    ? interp.callValue(accessorGetter, receiver, List.of())
                    : JsUndefined.getInstance();
        }
        if (!object.has(key)) {
            final var inherited = chainMember(object.getProto(), key, receiver);
            if (inherited != null) {
                return inherited;
            }
            final var intrinsic = intrinsicMember(object, key);
            if (!(intrinsic instanceof JsUndefined)) {
                return intrinsic;
            }
            if (AsyncIteratorBuiltins.isHelperName(key) && isAsyncIteratorLike(object)) {
                return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
            }
            if (IteratorBuiltins.isHelperName(key) && isIteratorLike(object)) {
                return IteratorBuiltins.helper(interp.ops(), key, interp.intrinsics().objectProto());
            }
            if (object.getPrimitive() != null) {
                return getMember(object.getPrimitive(), key);
            }
        }
        return object.get(key);
    }

    private JsValue getGlobalMember(JsGlobalObject global, String key) {
        final var value = global.getEnv().tryGet(key);
        if (value != null) {
            return value;
        }
        // A global accessor property cannot live in an Environment binding, so it sits in the
        // ordinary table and is read through the generic descriptor path.
        return orUndefined(fromDescriptor(global.getOwnProperty(new JsString(key)), global));
    }

    private JsValue getArgumentsMember(JsArguments arguments, String key) {
        final var descriptor = arguments.getOwnProperty(new JsString(key));
        if (descriptor == null) {
            return intrinsicMember(arguments, key);
        }
        if (!descriptor.isAccessorDescriptor()) {
            return descriptor.value();
        }
        return isCallable(descriptor.getter())
                ? interp.callValue(descriptor.getter(), arguments, List.of())
                : JsUndefined.getInstance();
    }

    private boolean setArgumentsMember(JsArguments arguments, String key, JsValue value) {
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

    // The last dispatch step for every value type: walk the realm's intrinsic prototype chain, so a
    // monkey-patched or user-added member on e.g. Array.prototype is what a receiver resolves to.
    private JsValue intrinsicMember(JsValue target, String key) {
        // Every caller reaches here only after its own exotic slots have missed, so an ordinary own
        // entry still has to be answered before the prototype chain - a Date or Map carries a
        // PropertyTable that nothing else on its read path looks at.
        final var table = target.ownProperties();
        if (table != null) {
            if (table.hasAccessor(key)) {
                final var getter = table.getAccessorGetter(key);
                return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, target, List.of());
            }
            if (table.has(key)) {
                return table.get(key);
            }
        }
        return orUndefined(chainMember(interp.intrinsics().protoFor(target), key, target));
    }

    private JsValue functionMember(JsValue function, String key) {
        final var table = function.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, function, List.of());
        }
        switch (function) {
            case JsCallableProperties callable when callable.hasProperty(key) -> {
                return callable.getProperty(key);
            }
            case JsFunction fn when "prototype".equals(key) -> {
                return fn.getPrototype();
            }
            case JsNativeFunction nf when "prototype".equals(key) -> {
                return orUndefined(nf.getPrototype());
            }
            case JsCallableProperties callable when callable.isMetadataDeleted(key) -> {
                return JsUndefined.getInstance();
            }
            default -> {
            }
        }
        final var metadata = FunctionProtoBuiltins.metadata(function, key);
        if (metadata != null) {
            return metadata;
        }
        // A native constructor with a real spec-level [[Prototype]] (each NativeError's is %Error%,
        // each concrete typed array's is %TypedArray%) inherits that constructor's statics before
        // falling through to Function.prototype.
        if (function instanceof JsNativeFunction nf && nf.getOwnProto() != null) {
            final var inherited = chainMember(nf.getOwnProto(), key, function);
            if (inherited != null) {
                return inherited;
            }
        }
        return intrinsicMember(function, key);
    }

    private JsValue mapMember(JsMap map, String key) {
        if ("size".equals(key)) {
            return new JsNumber(map.size());
        }
        return intrinsicMember(map, key);
    }

    private JsValue jsSetMember(JsSet set, String key) {
        if ("size".equals(key)) {
            return new JsNumber(set.size());
        }
        return intrinsicMember(set, key);
    }

    private JsValue dateMember(JsDate date, String key) {
        return intrinsicMember(date, key);
    }

    private JsValue bufferMember(JsArrayBuffer buffer, String key) {
        if (TypedArrayBuiltins.isBufferAccessor(key)) {
            return orUndefined(TypedArrayBuiltins.bufferMethod(buffer, key));
        }
        return intrinsicMember(buffer, key);
    }

    private JsValue dataViewMember(JsDataView view, String key) {
        if (TypedArrayBuiltins.isViewAccessor(key)) {
            return orUndefined(TypedArrayBuiltins.dataViewMethod(view, key));
        }
        return intrinsicMember(view, key);
    }

    private JsValue typedArrayMember(JsTypedArray typed, String key) {
        switch (key) {
            case "length" -> {
                return new JsNumber(typed.length());
            }
            case "byteLength" -> {
                return new JsNumber(typed.byteLength());
            }
            case "byteOffset" -> {
                return new JsNumber(typed.byteOffset());
            }
            case "buffer" -> {
                return typed.getBuffer();
            }
            case "BYTES_PER_ELEMENT" -> {
                return new JsNumber(typed.kind().bytesPerElement());
            }
            default -> {
            }
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return typed.getElement(index);
        }
        // A CanonicalNumericIndexString that isn't a valid array index (negative, non-integer,
        // out of Integer range, "-0", "NaN", "Infinity", ...) resolves via the exotic
        // IntegerIndexedElementGet, which returns undefined directly - it must never fall through
        // to the prototype chain the way an ordinary property miss would.
        if (InterpreterUtils.isCanonicalNumericIndexString(key)) {
            return JsUndefined.getInstance();
        }
        final var table = typed.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, typed, List.of());
        }
        return table.has(key) ? table.get(key) : intrinsicMember(typed, key);
    }

    private JsValue numberMember(JsNumber number, String key) {
        return intrinsicMember(number, key);
    }

    private JsValue symbolMember(JsSymbol symbol, String key) {
        final var property = SymbolBuiltins.getProperty(symbol, key);
        if (property != null) {
            return property;
        }
        return intrinsicMember(symbol, key);
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
            // A hole is not an own property, so the lookup continues up the prototype chain.
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
                ? intrinsicMember(array, key)
                : orUndefined(chainMember(array.getProto(), key, array));
    }

    private JsValue getStringMember(JsString string, String key) {
        if ("length".equals(key)) {
            return new JsNumber(string.getValue().length());
        }
        final var index = arrayIndex(key);
        if (index != null) {
            return index < string.getValue().length()
                    ? new JsString(String.valueOf(string.getValue().charAt(index)))
                    : JsUndefined.getInstance();
        }
        return intrinsicMember(string, key);
    }

    public boolean setMember(JsValue target, String key, JsValue value, JsValue receiver) {
        // An integer-indexed write is the view's own [[Set]]: it either answers the write itself or,
        // for a live index meant for a foreign receiver, leaves an ordinary property on that
        // receiver - never a write through to the view.
        if (target instanceof JsTypedArray typed && typed.hasCanonicalNumericIndex(new JsString(key))) {
            return typed.setExoticIndex(new JsString(key), value, receiver) || setOnReceiver(receiver, key, value);
        }
        if (target instanceof JsObject object) {
            return setObjectMember(object, key, value, receiver);
        }
        return setMember(target, key, value);
    }

    private boolean setObjectMember(JsObject object, String key, JsValue value, JsValue receiver) {
        if (!object.has(key)) {
            for (var chain = new Chain(object); chain.hasLink(); chain.advance()) {
                if (chain.link() instanceof JsProxy proxy) {
                    return proxies.set(proxy, new JsString(key), value, receiver);
                }
                // An integer-indexed link answers the whole write itself: the index is either
                // dropped or created on the receiver, and no accessor further up the chain (the
                // per-kind prototype included) is ever consulted.
                if (chain.link() instanceof JsTypedArray typed && typed.hasCanonicalNumericIndex(new JsString(key))) {
                    return typed.setExoticIndex(new JsString(key), value, receiver)
                            || setOnReceiver(receiver, key, value);
                }
                final var accessor = protoAccessor(chain.link(), key);
                if (accessor != null) {
                    if (!isCallable(accessor.setter())) {
                        return false;
                    }
                    interp.callValue(accessor.setter(), receiver, List.of(value));
                    return true;
                }
            }
        }
        // A proxy receiver would need its own [[DefineOwnProperty]] here; routing there turns a
        // rejected define into a thrown TypeError where OrdinarySet owes the caller a false, so the
        // write stays on the object it resolved against.
        return receiver == object || receiver instanceof JsProxy
                ? object.set(key, value)
                : setOnReceiver(receiver, key, value);
    }

    // OrdinarySet lands the write on the receiver, not on the object whose chain answered the lookup:
    // reached when a proxy or Reflect.set supplies a receiver other than the target.
    private boolean setOnReceiver(JsValue receiver, String key, JsValue value) {
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
                } else {
                    cls.setStaticProp(key, value);
                }
                yield true;
            }
            case JsArray array -> setArrayMember(array, key, value);
            // A plain assignment is the view's own [[Set]] with itself as the receiver, so every
            // CanonicalNumericIndexString coerces the value first and only then decides whether a
            // slot receives it; one that names no slot is discarded, never stored as an ordinary
            // own property.
            case JsTypedArray typed -> typed.setExoticIndex(new JsString(key), value, typed)
                    || setTableMember(typed, typed.ownProperties(), key, value);
            // PerformPromiseThen invokes the receiver's own `then`, and SpeciesConstructor reads its
            // own `constructor`, so an assignment to either has to land as an ordinary own property.
            case JsPromise promise -> promise.ownProperties().set(key, value);
            // lastIndex is an ordinary own data property, and every other key is one too: RegExpExec
            // calls the receiver's own `exec`, which a script can only override if the assignment
            // lands. A setterless inherited accessor (the flag getters) still refuses the write.
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
                // An ordinary function's `prototype` is a writable own property, so even a primitive
                // assignment is stored and observable through Get (`new` and
                // OrdinaryCreateFromConstructor fall back to the intrinsic when it is not an
                // object). A builtin's is not writable, and only an object-like value is kept there.
                if ("prototype".equals(key)) {
                    if (callable instanceof JsFunction fn) {
                        fn.setPrototype(value);
                    } else if (callable instanceof JsNativeFunction nf && InterpreterUtils.isObjectLike(value)) {
                        nf.setPrototype(value);
                    }
                } else {
                    callable.setEnumerableProperty(key, value);
                }
                yield true;
            }
            // Date, Map, Set, buffers, views and generators are ordinary objects apart from their
            // internal slot, so an assignment lands in the same table their descriptors are read
            // from; a primitive receiver has no table and discards the write.
            default -> {
                final var table = target.ownProperties();
                yield table == null || setTableMember(target, table, key, value);
            }
        };
    }

    private boolean setTableMember(JsValue target, PropertyTable table, String key, JsValue value) {
        if (table.hasAccessor(key)) {
            final var setter = table.getAccessorSetter(key);
            if (setter == null) {
                return false;
            }
            interp.callValue(setter, target, List.of(value));
            return true;
        }
        if (!table.has(key)) {
            final var accessor = inheritedAccessor(target, key);
            if (accessor != null) {
                return writeThroughAccessor(target, accessor, value);
            }
        }
        return table.set(key, value);
    }

    // OrdinarySet's prototype step: the nearest accessor the receiver inherits for the key, or null
    // when the chain holds none and the write should create an own property instead.
    private PropertyDescriptor inheritedAccessor(JsValue target, String key) {
        return chainAccessor(protoChainStart(target), key);
    }

    // An inherited accessor takes the write; a getter-only one rejects it.
    private boolean writeThroughAccessor(JsValue target, PropertyDescriptor accessor, JsValue value) {
        if (!isCallable(accessor.setter())) {
            return false;
        }
        interp.callValue(accessor.setter(), target, List.of(value));
        return true;
    }

    // The nearest inherited accessor for the key, or null when the chain holds none.
    private PropertyDescriptor chainAccessor(JsValue start, String key) {
        for (var chain = new Chain(start); chain.hasLink(); chain.advance()) {
            final var accessor = protoAccessor(chain.link(), key);
            if (accessor != null) {
                return accessor;
            }
        }
        return null;
    }

    // An explicit [[Prototype]] wins; without one the value inherits from the realm's intrinsic
    // prototype for its type.
    private JsValue protoChainStart(JsValue target) {
        final var proto = target.getProto();
        return proto == null ? interp.intrinsics().protoFor(target) : proto;
    }

    private static boolean isNonWritableMetadata(JsCallableProperties callable, String key) {
        return ("name".equals(key) || "length".equals(key)) && !callable.hasProperty(key)
                && !callable.isMetadataDeleted(key);
    }

    public static String writeRejectionMessage(JsValue target, JsValue key) {
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
        if (target instanceof JsArray array && array.isFrozen()) {
            return "Cannot assign to read only property '" + name + "' of object";
        }
        return "Cannot add property " + name + ", object is not extensible";
    }

    private boolean setArrayMember(JsArray array, String key, JsValue value) {
        if ("length".equals(key)) {
            return array.setLength(requireLength(value));
        }
        if (array.hasPropAccessor(key)) {
            final var setter = array.getPropAccessorSetter(key);
            if (setter != null) {
                interp.callValue(setter, array, List.of(value));
            }
            return true;
        }
        final var index = arrayIndex(key);
        if (index != null && array.hasIndexAccessor(index)) {
            final var setter = array.getIndexAccessorSetter(index);
            if (setter != null) {
                interp.callValue(setter, array, List.of(value));
            }
            return true;
        }
        if (!arrayOwnsKey(array, key, index)) {
            final var accessor = inheritedAccessor(array, key);
            if (accessor != null) {
                return writeThroughAccessor(array, accessor, value);
            }
        }
        return index == null ? array.setProperty(key, value) : array.set(index, value);
    }

    private static boolean arrayOwnsKey(JsArray array, String key, Integer index) {
        return index == null ? array.hasProperty(key) : index < array.length() && !array.isHole(index);
    }

    private static int requireLength(JsValue value) {
        final var length = JsCoercion.toNumber(value);
        if (Double.isNaN(length) || length < 0 || length != Math.floor(length)) {
            throw new RangeErrorException("Invalid array length");
        }
        return (int) length;
    }

    private JsValue generatorMethod(JsGenerator generator, String key) {
        final var intrinsic = intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !IteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return IteratorBuiltins.helper(interp.ops(), key, interp.intrinsics().objectProto());
    }

    // Checks the instance and its immediate prototype (not the whole chain) so a class extending
    // Iterator with a `next()` instance method - stored on its own prototype, not the instance
    // itself - is still recognised. Stopping at one level avoids reaching a shared native
    // superclass prototype (e.g. Iterator's, which aliases Generator.prototype and always carries
    // a generic `next` wrapper) that would otherwise make every Iterator subclass instance a false
    // positive regardless of whether it defines its own `next`.
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

    private JsValue asyncGeneratorMethod(JsAsyncGenerator generator, String key) {
        final var intrinsic = intrinsicMember(generator, key);
        if (!(intrinsic instanceof JsUndefined) || !AsyncIteratorBuiltins.isHelperName(key)) {
            return intrinsic;
        }
        return AsyncIteratorBuiltins.helper(interp.ops(), eventLoop, key);
    }

    // The spec's [[AsyncGeneratorQueue]] and [[AsyncGeneratorState]]: a request is always appended,
    // and only a generator that is not already running (or awaiting a return) starts draining, so a
    // re-entrant next()/return()/throw() from the generator's own body queues instead of deadlocking.
    public JsValue driveAsyncGenerator(JsAsyncGenerator generator, AsyncStep kind, JsValue arg) {
        final var promise = new JsPromise(eventLoop);
        generator.enqueue(new JsAsyncGenerator.Request(requestKind(kind), arg, promise));
        final var state = generator.getState();
        if (state != JsAsyncGenerator.State.EXECUTING && state != JsAsyncGenerator.State.AWAITING_RETURN) {
            drainAsyncGenerator(generator);
        }
        return promise;
    }

    private static JsAsyncGenerator.RequestKind requestKind(AsyncStep kind) {
        return switch (kind) {
            case NEXT -> JsAsyncGenerator.RequestKind.NEXT;
            case RETURN -> JsAsyncGenerator.RequestKind.RETURN;
            case THROW -> JsAsyncGenerator.RequestKind.THROW;
        };
    }

    private void drainAsyncGenerator(JsAsyncGenerator generator) {
        final var coroutine = generator.getCoroutine();
        var draining = true;
        while (draining && generator.hasRequests()) {
            final var state = generator.getState();
            final var request = generator.peekRequest();
            final var startOnly = state == JsAsyncGenerator.State.SUSPENDED_START
                    && request.kind() != JsAsyncGenerator.RequestKind.NEXT;
            if (state == JsAsyncGenerator.State.EXECUTING || state == JsAsyncGenerator.State.AWAITING_RETURN) {
                draining = false;
            } else if (state == JsAsyncGenerator.State.COMPLETED || coroutine.isDone() || startOnly) {
                draining = settleWithoutResuming(generator, request);
            } else {
                startRequest(generator, request);
                draining = false;
            }
        }
    }

    private void startRequest(JsAsyncGenerator generator, JsAsyncGenerator.Request request) {
        generator.setState(JsAsyncGenerator.State.EXECUTING);
        if (request.kind() == JsAsyncGenerator.RequestKind.RETURN) {
            // AsyncGeneratorUnwrapYieldResumption awaits a return completion's value before the body
            // resumes, so `gen.return(promise)` unwraps rather than returning the promise itself.
            interp.toPromise(request.value()).subscribe(
                    value -> resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.RETURN, value),
                    reason -> resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.THROW, reason));
            return;
        }
        resumeAsyncGenerator(generator, request.kind(), request.value());
    }

    // Returns false when the request is settled asynchronously (AsyncGeneratorAwaitReturn), so the
    // caller stops draining and lets the await's continuation resume it.
    private boolean settleWithoutResuming(JsAsyncGenerator generator, JsAsyncGenerator.Request request) {
        if (request.kind() == JsAsyncGenerator.RequestKind.RETURN) {
            generator.setState(JsAsyncGenerator.State.AWAITING_RETURN);
            interp.toPromise(request.value()).subscribe(value -> {
                generator.setState(JsAsyncGenerator.State.COMPLETED);
                generator.pollRequest();
                request.capability().resolve(stepResult(value, true));
                drainAsyncGenerator(generator);
            }, reason -> {
                generator.setState(JsAsyncGenerator.State.COMPLETED);
                generator.pollRequest();
                request.capability().reject(reason);
                drainAsyncGenerator(generator);
            });
            return false;
        }
        generator.setState(JsAsyncGenerator.State.COMPLETED);
        generator.pollRequest();
        if (request.kind() == JsAsyncGenerator.RequestKind.THROW) {
            request.capability().reject(request.value());
        } else {
            request.capability().resolve(stepResult(JsUndefined.getInstance(), true));
        }
        return true;
    }

    private void resumeAsyncGenerator(JsAsyncGenerator generator, JsAsyncGenerator.RequestKind kind, JsValue value) {
        final var coroutine = generator.getCoroutine();
        try {
            switch (kind) {
                case RETURN -> coroutine.resumeReturn(value);
                case THROW -> coroutine.resumeThrow(value);
                default -> coroutine.resumeNext(value);
            }
        } catch (SimpleJsRuntimeException error) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeStep(generator, error);
        }
    }

    public void observeAsyncGenerator(JsAsyncGenerator generator, RuntimeException escaped) {
        final var coroutine = generator.getCoroutine();
        if (escaped != null) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeStep(generator, escaped);
            return;
        }
        if (coroutine.isDone()) {
            generator.setState(JsAsyncGenerator.State.COMPLETED);
            completeResolve(generator, coroutine.completedValue(), true);
            return;
        }
        if (coroutine.pauseReason() == Coroutine.PauseReason.YIELD) {
            yieldStep(generator, coroutine.yieldedValue());
        }
    }

    // AsyncGeneratorYield awaits the yielded operand before the step settles, so `yield promise`
    // hands the consumer the promise's value and a rejection re-enters the body at the yield.
    private void yieldStep(JsAsyncGenerator generator, JsValue value) {
        if (generator.getCoroutine().isDelegatedYield()) {
            generator.setState(JsAsyncGenerator.State.SUSPENDED_YIELD);
            completeResolve(generator, value, false);
            return;
        }
        generator.setState(JsAsyncGenerator.State.EXECUTING);
        interp.toPromise(value).subscribe(settled -> {
            generator.setState(JsAsyncGenerator.State.SUSPENDED_YIELD);
            completeResolve(generator, settled, false);
        }, reason -> {
            generator.setState(JsAsyncGenerator.State.EXECUTING);
            resumeAsyncGenerator(generator, JsAsyncGenerator.RequestKind.THROW, reason);
        });
    }

    private void completeResolve(JsAsyncGenerator generator, JsValue value, boolean done) {
        final var request = generator.pollRequest();
        if (request != null) {
            request.capability().resolve(stepResult(value, done));
        }
        drainAsyncGenerator(generator);
    }

    private void completeStep(JsAsyncGenerator generator, RuntimeException error) {
        if (!(error instanceof SimpleJsRuntimeException)) {
            throw error;
        }
        final var request = generator.pollRequest();
        if (request != null) {
            request.capability().reject(toErrorValue(error, interp.intrinsics()));
        }
        drainAsyncGenerator(generator);
    }

    private JsValue regExpMember(JsRegExp regexp, String key) {
        final var table = regexp.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, regexp, List.of());
        }
        // An own property shadows the inherited flag accessor, so defineProperty(re, 'flags', …) wins.
        if (table.has(key)) {
            return table.get(key);
        }
        if (RegexBuiltins.isAccessor(key)) {
            return orUndefined(RegexBuiltins.getMethod(regexp, key));
        }
        return intrinsicMember(regexp, key);
    }

    private JsValue promiseMethod(JsPromise promise, String key) {
        final var table = promise.ownProperties();
        if (table.hasAccessor(key)) {
            final var getter = table.getAccessorGetter(key);
            return getter == null ? JsUndefined.getInstance() : interp.callValue(getter, promise, List.of());
        }
        return table.has(key) ? table.get(key) : intrinsicMember(promise, key);
    }
}
