package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.values.JsValue;

public interface InterpreterOps {
    JsValue getMember(JsValue target, JsValue key);

    JsValue getMemberWithReceiver(JsValue target, JsValue key, JsValue receiver);

    boolean setMember(JsValue target, JsValue key, JsValue value);

    boolean setMemberWithReceiver(JsValue target, JsValue key, JsValue value, JsValue receiver);

    boolean has(JsValue target, JsValue key);

    boolean deleteMember(JsValue target, JsValue key);

    List<JsValue> ownKeys(JsValue target);

    JsValue call(JsValue fn, JsValue thisArg, List<JsValue> args);

    JsValue construct(JsValue fn, List<JsValue> args, JsValue newTarget);

    default JsValue construct(JsValue fn, List<JsValue> args) {
        return construct(fn, args, fn);
    }

    JsValue getPrototypeOf(JsValue target);

    boolean setPrototypeOf(JsValue target, JsValue proto);

    boolean isExtensible(JsValue target);

    boolean preventExtensions(JsValue target);

    boolean defineProperty(JsValue target, JsValue key, JsValue descriptor);

    JsValue getOwnPropertyDescriptor(JsValue target, JsValue key);

    // The single seam the static builtins families reach the host's locale/time zone through, so a
    // Date/Temporal/toLocaleString answer is the host's choice rather than the JVM's.
    default java.time.ZoneId timeZone() {
        return java.time.ZoneId.systemDefault();
    }

    default java.util.Locale locale() {
        return java.util.Locale.getDefault();
    }

    // Bulk-allocation metering. tick() bounds allocation costing an instruction per unit; these charge
    // the allocations that are O(N) in one instruction, which it cannot see. The cost model is these
    // two constants and nothing else, so it stays auditable in one place.
    long STRING_BYTES_PER_CHAR = 2L;
    long BYTES_PER_ELEMENT = 32L;

    default void charge(long bytes) {
    }

    // A native loop bounded by a script-supplied length allocates little but can run to 2^53, which
    // neither the instruction budget (no tick inside a builtin) nor the memory budget can see.
    default void tick() {
    }

    static void tick(InterpreterOps ops) {
        if (ops != null) {
            ops.tick();
        }
    }

    static void charge(InterpreterOps ops, long bytes) {
        if (ops != null) {
            ops.charge(bytes);
        }
    }

    static void chargeChars(InterpreterOps ops, long chars) {
        charge(ops, chars * STRING_BYTES_PER_CHAR);
    }

    static void chargeElements(InterpreterOps ops, long count) {
        charge(ops, count * BYTES_PER_ELEMENT);
    }

    // Several getMethod overloads are reachable with a null seam (the no-ops convenience variants),
    // so the two readers below are the single place that decides what "no host" means.
    static java.time.ZoneId timeZone(InterpreterOps ops) {
        return ops == null ? java.time.ZoneId.systemDefault() : ops.timeZone();
    }

    static java.util.Locale locale(InterpreterOps ops) {
        return ops == null ? java.util.Locale.getDefault() : ops.locale();
    }
}
