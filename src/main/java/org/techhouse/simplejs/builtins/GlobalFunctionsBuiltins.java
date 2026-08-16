package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class GlobalFunctionsBuiltins {
    private static final String URI_UNESCAPED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            + "-_.!~*'()";
    private static final String URI_RESERVED = ";/?:@&=+$,#";
    private static final String ESCAPE_UNESCAPED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            + "@*_+-./";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private GlobalFunctionsBuiltins() {
    }

    public static void install(Environment global, EventLoop eventLoop, Invoker invoker, InterpreterOps ops,
            Intrinsics intrinsics) {
        define(global, "encodeURI", new JsNativeFunction("encodeURI",
                (_, args) -> new JsString(encode(str(args), URI_UNESCAPED + URI_RESERVED, intrinsics))));
        define(global, "encodeURIComponent", new JsNativeFunction("encodeURIComponent",
                (_, args) -> new JsString(encode(str(args), URI_UNESCAPED, intrinsics))));
        define(global, "decodeURI",
                new JsNativeFunction("decodeURI", (_, args) -> new JsString(decode(str(args), true, intrinsics))));
        define(global, "decodeURIComponent", new JsNativeFunction("decodeURIComponent",
                (_, args) -> new JsString(decode(str(args), false, intrinsics))));
        define(global, "escape", new JsNativeFunction("escape", (_, args) -> new JsString(escape(str(args)))));
        define(global, "unescape", new JsNativeFunction("unescape", (_, args) -> new JsString(unescape(str(args)))));
        define(global, "structuredClone",
                new JsNativeFunction("structuredClone",
                        (_, args) -> clone(args.isEmpty() ? JsUndefined.getInstance() : args.getFirst(),
                                new IdentityHashMap<>(), ops)));
        define(global, "queueMicrotask", new JsNativeFunction("queueMicrotask", (_, args) -> {
            final var callback = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (!(callback instanceof JsFunction) && !(callback instanceof JsNativeFunction)) {
                throw new TypeErrorException("queueMicrotask callback is not a function");
            }
            eventLoop.queueMicrotask(() -> invoker.call(callback, JsUndefined.getInstance(), List.of()));
            return JsUndefined.getInstance();
        }));
    }

    private static String encode(String input, String unescaped, Intrinsics intrinsics) {
        final var out = new StringBuilder();
        var i = 0;
        while (i < input.length()) {
            final var cp = input.codePointAt(i);
            if (cp < 128 && unescaped.indexOf((char) cp) >= 0) {
                out.append((char) cp);
            } else if (cp >= 0xD800 && cp <= 0xDFFF) {
                throw uriError(intrinsics);
            } else {
                for (final var b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String decode(String input, boolean preserveReserved, Intrinsics intrinsics) {
        final var out = new StringBuilder();
        var i = 0;
        while (i < input.length()) {
            final var c = input.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            final var first = hexByte(input, i, intrinsics);
            if ((first & 0x80) == 0) {
                if (preserveReserved && URI_RESERVED.indexOf((char) first) >= 0) {
                    out.append(input, i, i + 3);
                } else {
                    out.append((char) first);
                }
                i += 3;
            } else {
                final var count = leadingByteCount(first, intrinsics);
                final var bytes = new byte[count];
                bytes[0] = (byte) first;
                var pos = i + 3;
                for (var k = 1; k < count; k++) {
                    if (pos >= input.length() || input.charAt(pos) != '%') {
                        throw uriError(intrinsics);
                    }
                    final var next = hexByte(input, pos, intrinsics);
                    if ((next & 0xC0) != 0x80) {
                        throw uriError(intrinsics);
                    }
                    bytes[k] = (byte) next;
                    pos += 3;
                }
                out.append(new String(bytes, StandardCharsets.UTF_8));
                i = pos;
            }
        }
        return out.toString();
    }

    private static int leadingByteCount(int first, Intrinsics intrinsics) {
        if ((first & 0xE0) == 0xC0) {
            return 2;
        }
        if ((first & 0xF0) == 0xE0) {
            return 3;
        }
        if ((first & 0xF8) == 0xF0) {
            return 4;
        }
        throw uriError(intrinsics);
    }

    private static int hexByte(String input, int index, Intrinsics intrinsics) {
        if (index + 2 >= input.length()) {
            throw uriError(intrinsics);
        }
        final var high = Character.digit(input.charAt(index + 1), 16);
        final var low = Character.digit(input.charAt(index + 2), 16);
        if (high < 0 || low < 0) {
            throw uriError(intrinsics);
        }
        return (high << 4) | low;
    }

    private static String escape(String input) {
        final var out = new StringBuilder();
        for (var i = 0; i < input.length(); i++) {
            final var c = input.charAt(i);
            if (c < 256 && ESCAPE_UNESCAPED.indexOf(c) >= 0) {
                out.append(c);
            } else if (c < 256) {
                out.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            } else {
                out.append("%u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF]).append(HEX[(c >> 4) & 0xF])
                        .append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    private static String unescape(String input) {
        final var out = new StringBuilder();
        var i = 0;
        while (i < input.length()) {
            final var c = input.charAt(i);
            if (c == '%' && i + 1 < input.length() && input.charAt(i + 1) == 'u' && isHex(input, i + 2, 4)) {
                out.append((char) Integer.parseInt(input.substring(i + 2, i + 6), 16));
                i += 6;
            } else if (c == '%' && isHex(input, i + 1, 2)) {
                out.append((char) Integer.parseInt(input.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isHex(String input, int start, int length) {
        if (start + length > input.length()) {
            return false;
        }
        for (var i = start; i < start + length; i++) {
            if (Character.digit(input.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static JsValue clone(JsValue value, Map<JsValue, JsValue> seen, InterpreterOps ops) {
        switch (value) {
            case JsNumber ignored -> {
                return value;
            }
            case JsString ignored -> {
                return value;
            }
            case JsBoolean ignored -> {
                return value;
            }
            case JsBigInt ignored -> {
                return value;
            }
            case JsNull ignored -> {
                return value;
            }
            case JsUndefined ignored -> {
                return value;
            }
            case JsFunction ignored -> throw dataCloneError(value);
            case JsNativeFunction ignored -> throw dataCloneError(value);
            case JsSymbol ignored -> throw dataCloneError(value);
            case JsProxy ignored -> throw dataCloneError(value);
            default -> {
                final var existing = seen.get(value);
                return Objects.requireNonNullElseGet(existing, () -> cloneStructured(value, seen, ops));
            }
        }
    }

    private static JsValue cloneStructured(JsValue value, Map<JsValue, JsValue> seen, InterpreterOps ops) {
        return switch (value) {
            case JsArray array -> {
                final var copy = new JsArray();
                seen.put(value, copy);
                for (final var element : array.getElements()) {
                    copy.push(clone(element, seen, ops));
                }
                yield copy;
            }
            case JsDate date -> new JsDate(date.getTime());
            case JsArrayBuffer buffer -> new JsArrayBuffer(buffer.getBytes().clone());
            case JsTypedArray typed -> {
                final var buffer = new JsArrayBuffer(typed.getBuffer().getBytes().clone());
                yield new JsTypedArray(typed.kind(), buffer, typed.byteOffset(), typed.length());
            }
            case JsMap map -> {
                final var copy = new JsMap();
                seen.put(value, copy);
                for (final var entry : map.entries()) {
                    copy.set(clone(entry.key(), seen, ops), clone(entry.value(), seen, ops));
                }
                yield copy;
            }
            case JsSet set -> {
                final var copy = new JsSet();
                seen.put(value, copy);
                for (final var element : set.values()) {
                    copy.add(clone(element, seen, ops));
                }
                yield copy;
            }
            case JsObject object -> {
                final var copy = new JsObject();
                seen.put(value, copy);
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        copy.set(key, clone(ownValue(object, key, ops), seen, ops));
                    }
                }
                yield copy;
            }
            default -> throw dataCloneError(value);
        };
    }

    private static TypeErrorException dataCloneError(JsValue value) {
        return new TypeErrorException(JsCoercion.toStr(value) + " could not be cloned");
    }

    private static JsThrowException uriError(Intrinsics intrinsics) {
        return new JsThrowException(intrinsics.makeError("URIError", "URI malformed"));
    }

    private static String str(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }

    private static void define(Environment global, String name, JsValue value) {
        global.declareBuiltin(name, value);
    }
}
