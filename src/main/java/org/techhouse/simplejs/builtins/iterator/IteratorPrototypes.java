package org.techhouse.simplejs.builtins.iterator;

import static org.techhouse.simplejs.builtins.IteratorBuiltins.HELPER_STATE;
import static org.techhouse.simplejs.builtins.IteratorBuiltins.WRAP_STATE;
import static org.techhouse.simplejs.builtins.IteratorBuiltins.step;
import static org.techhouse.simplejs.builtins.IteratorBuiltins.stepOf;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.HIDDEN;
import static org.techhouse.simplejs.values.JsObject.PropertyFlags.TAG;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.IteratorBuiltins.HelperState;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class IteratorPrototypes {
    public static void installIteratorSymbol(JsObject prototype) {
        final var iterator = new JsNativeFunction("[Symbol.iterator]", (thisArg, _) -> thisArg);
        iterator.setLength(0);
        prototype.setSymbol(JsSymbol.ITERATOR, iterator);
        prototype.setSymbolFlags(JsSymbol.ITERATOR, HIDDEN);
    }

    public static void installDispose(InterpreterOps ops, JsObject prototype) {
        final var dispose = new JsNativeFunction("[Symbol.dispose]", (thisArg, _) -> {
            final var returnFn = ops.getMember(thisArg, new JsString("return"));
            if (!InterpreterUtils.isNullish(returnFn)) {
                if (!isCallable(returnFn)) {
                    throw new TypeErrorException("iterator.return is not a function");
                }
                ops.call(returnFn, thisArg, List.of());
            }
            return JsUndefined.getInstance();
        });
        dispose.setLength(0);
        prototype.setSymbol(JsSymbol.DISPOSE, dispose);
        prototype.setSymbolFlags(JsSymbol.DISPOSE, HIDDEN);
    }

    public static void installIgnoringAccessor(JsObject home, String label, JsSymbol symbolKey, JsValue value,
            InterpreterOps ops) {
        final var getter = new JsNativeFunction("get " + label, (_, _) -> value);
        getter.setLength(0);
        final var setter = new JsNativeFunction("set " + label, (thisArg, args) -> {
            if (!InterpreterUtils.isObjectLike(thisArg)) {
                throw new TypeErrorException("Cannot set " + label + " on a non-object");
            }
            if (thisArg == home) {
                throw new TypeErrorException(label + " is not writable on Iterator.prototype");
            }
            final var key = symbolKey == null ? new JsString(label) : symbolKey;
            final var incoming = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (ops.getOwnPropertyDescriptor(thisArg, key) instanceof JsUndefined) {
                ops.defineProperty(thisArg, key, descriptor(incoming));
            } else {
                ops.setMember(thisArg, key, incoming);
            }
            return JsUndefined.getInstance();
        });
        setter.setLength(1);
        if (symbolKey == null) {
            home.defineAccessor(label, getter, setter);
            home.setFlags(label, HIDDEN);
        } else {
            home.defineSymbolAccessor(symbolKey, getter, setter);
            home.setSymbolFlags(symbolKey, HIDDEN);
        }
    }

    public static JsObject descriptor(JsValue value) {
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.TRUE);
        descriptor.set("enumerable", JsBoolean.TRUE);
        descriptor.set("configurable", JsBoolean.TRUE);
        return descriptor;
    }

    public static JsObject helperPrototype(JsObject iteratorProto, JsObject objectProto) {
        final var proto = new JsObject();
        proto.setProto(iteratorProto);
        final var next = new JsNativeFunction("next", (thisArg, _) -> {
            final var state = requireHelper(thisArg);
            return state.run(() -> stepOf(state.closed ? null : state.nextValue.get(), state, objectProto));
        });
        next.setLength(0);
        Intrinsics.defineHidden(proto, "next", next);
        final var close = new JsNativeFunction("return", (thisArg, _) -> {
            final var state = requireHelper(thisArg);
            return state.run(() -> {
                if (!state.closed) {
                    state.closed = true;
                    state.onClose.run();
                }
                return step(null, objectProto);
            });
        });
        close.setLength(0);
        Intrinsics.defineHidden(proto, "return", close);
        proto.setSymbol(JsSymbol.TO_STRING_TAG, new JsString("Iterator Helper"));
        proto.setSymbolFlags(JsSymbol.TO_STRING_TAG, TAG);
        return proto;
    }

    public static JsObject wrapPrototype(InterpreterOps ops, JsObject iteratorProto, JsObject objectProto) {
        final var proto = new JsObject();
        proto.setProto(iteratorProto);
        final var next = new JsNativeFunction("next", (thisArg, _) -> {
            final var state = requireWrap(thisArg);
            if (!isCallable(state.nextMethod)) {
                throw new TypeErrorException("iterator.next is not a function");
            }
            return ops.call(state.nextMethod, state.iterator, List.of());
        });
        next.setLength(0);
        Intrinsics.defineHidden(proto, "next", next);
        final var close = new JsNativeFunction("return", (thisArg, _) -> {
            final var state = requireWrap(thisArg);
            final var returnFn = ops.getMember(state.iterator, new JsString("return"));
            if (InterpreterUtils.isNullish(returnFn)) {
                return step(null, objectProto);
            }
            if (!isCallable(returnFn)) {
                throw new TypeErrorException("iterator.return is not a function");
            }
            return ops.call(returnFn, state.iterator, List.of());
        });
        close.setLength(0);
        Intrinsics.defineHidden(proto, "return", close);
        return proto;
    }

    public static HelperState requireHelper(JsValue receiver) {
        final var state = receiver instanceof JsObject object ? HELPER_STATE.get(object) : null;
        if (state == null) {
            throw new TypeErrorException("Iterator Helper method called on an incompatible receiver");
        }
        return state;
    }

    public static WrapState requireWrap(JsValue receiver) {
        final var state = receiver instanceof JsObject object ? WRAP_STATE.get(object) : null;
        if (state == null) {
            throw new TypeErrorException("Iterator wrapper method called on an incompatible receiver");
        }
        return state;
    }

    public record WrapState(JsValue iterator, JsValue nextMethod) {
    }

    private IteratorPrototypes() {
    }
}
