package org.techhouse.simplejs.builtins.string;

import static org.techhouse.simplejs.builtins.BuiltinArgs.str;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.RegexBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class StringRegExpDelegation {
    public static JsValue delegateToSymbol(String value, List<JsValue> args, InterpreterOps ops, List<JsValue> extra) {
        if (ops == null || args.isEmpty()) {
            return null;
        }
        final var pattern = args.getFirst();
        if (!InterpreterUtils.isObjectLike(pattern)) {
            return null;
        }
        final var method = ops.getMember(pattern, JsSymbol.MATCH_ALL);
        if (!(method instanceof JsFunction) && !(method instanceof JsNativeFunction)) {
            return null;
        }
        final var callArgs = new ArrayList<JsValue>();
        callArgs.add(new JsString(value));
        callArgs.addAll(extra);
        return ops.call(method, pattern, callArgs);
    }

    public static JsValue viaRegExp(String value, List<JsValue> args, String flags, JsSymbol symbol,
            InterpreterOps ops) {
        final var pattern = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var source = pattern instanceof JsUndefined ? "" : JsCoercion.toStr(pattern, ops);
        final var regexp = RegexTranslator.compile(source, flags);
        return ops.call(ops.getMember(regexp, symbol), regexp, List.of(new JsString(value)));
    }

    public static List<JsValue> tail(List<JsValue> args) {
        return args.size() > 1 ? args.subList(1, args.size()) : List.of();
    }

    public static boolean isGeneric(String name) {
        return switch (name) {
            case "split", "replace", "replaceAll", "match", "search" -> true;
            default -> false;
        };
    }

    public static JsNativeFunction genericMethod(String name, InterpreterOps ops, Invoker invoker) {
        if (!isGeneric(name)) {
            return null;
        }
        return new JsNativeFunction(name, (thisArg, args) -> genericDispatch(name, thisArg, args, ops, invoker));
    }

    public static JsValue genericDispatch(String name, JsValue thisArg, List<JsValue> args, InterpreterOps ops,
            Invoker invoker) {
        requireCoercible(thisArg, name);
        if ("replaceAll".equals(name)) {
            requireGlobalRegExp(args, "replaceAll", ops);
        }
        final var delegated = delegateToSymbolRaw(thisArg, args, genericSymbol(name), ops, genericExtra(name, args));
        if (delegated != null) {
            return delegated;
        }
        final var value = JsCoercion.toStr(thisArg, ops);
        return switch (name) {
            case "split" -> split(value, args, ops);
            case "replace" -> replace(value, args, invoker, false, ops);
            case "replaceAll" -> replace(value, args, invoker, true, ops);
            case "match" -> viaRegExp(value, args, "", JsSymbol.MATCH, ops);
            case "search" -> viaRegExp(value, args, "", JsSymbol.SEARCH, ops);
            default -> throw new IllegalStateException(name);
        };
    }

    public static JsSymbol genericSymbol(String name) {
        return switch (name) {
            case "split" -> JsSymbol.SPLIT;
            case "replace", "replaceAll" -> JsSymbol.REPLACE;
            case "match" -> JsSymbol.MATCH;
            case "search" -> JsSymbol.SEARCH;
            default -> throw new IllegalStateException(name);
        };
    }

    public static List<JsValue> genericExtra(String name, List<JsValue> args) {
        return switch (name) {
            case "split", "replace", "replaceAll" -> tail(args);
            default -> List.of();
        };
    }

    public static void requireCoercible(JsValue receiver, String method) {
        if (receiver instanceof JsNull || receiver instanceof JsUndefined) {
            throw new TypeErrorException("String.prototype." + method + " called on null or undefined");
        }
    }

    public static JsValue delegateToSymbolRaw(JsValue receiver, List<JsValue> args, JsSymbol symbol, InterpreterOps ops,
            List<JsValue> extra) {
        if (ops == null || args.isEmpty()) {
            return null;
        }
        final var pattern = args.getFirst();
        if (!InterpreterUtils.isObjectLike(pattern)) {
            return null;
        }
        final var method = ops.getMember(pattern, symbol);
        if (method instanceof JsUndefined || method instanceof JsNull) {
            return null;
        }
        if (!(method instanceof JsFunction) && !(method instanceof JsNativeFunction)) {
            throw new TypeErrorException(symbol + " is not a function");
        }
        final var callArgs = new ArrayList<JsValue>();
        callArgs.add(receiver);
        callArgs.addAll(extra);
        return ops.call(method, pattern, callArgs);
    }

    public static boolean isRegExp(JsValue value, InterpreterOps ops) {
        if (!(value instanceof JsObject) && !(value instanceof JsRegExp)) {
            return false;
        }
        if (ops != null) {
            final var matcher = ops.getMember(value, JsSymbol.MATCH);
            if (!(matcher instanceof JsUndefined)) {
                return JsCoercion.toBoolean(matcher);
            }
        }
        return value instanceof JsRegExp;
    }

    public static void requireNotRegExp(List<JsValue> args, String method, InterpreterOps ops) {
        if (!args.isEmpty() && isRegExp(args.getFirst(), ops)) {
            throw new TypeErrorException(
                    "First argument to String.prototype." + method + " must not be a regular expression");
        }
    }

    public static void requireGlobalRegExp(List<JsValue> args, String method, InterpreterOps ops) {
        if (args.isEmpty() || !RegexBuiltins.isRegExp(args.getFirst(), ops)) {
            return;
        }
        final var flags = ops.getMember(args.getFirst(), new JsString("flags"));
        if (flags instanceof JsUndefined || flags instanceof JsNull) {
            throw new TypeErrorException("String.prototype." + method + " called with a RegExp without flags");
        }
        if (JsCoercion.toStr(flags, ops).indexOf('g') < 0) {
            throw new TypeErrorException("String.prototype." + method + " called with a non-global RegExp argument");
        }
    }

    public static JsValue split(String value, List<JsValue> args, InterpreterOps ops) {
        final var limit = args.size() < 2 || args.get(1) instanceof JsUndefined
                ? 0xFFFFFFFFL
                : NumberFormatter.toUint32(JsCoercion.toNumber(args.get(1), ops));
        final var undefinedSeparator = args.isEmpty() || args.getFirst() instanceof JsUndefined;
        final var separator = undefinedSeparator ? "" : str(args, 0, ops);
        if (limit == 0) {
            return new JsArray();
        }
        if (undefinedSeparator) {
            final var single = new JsArray();
            single.push(new JsString(value));
            return single;
        }
        return limited(splitAll(value, separator, ops), limit);
    }

    public static JsArray splitAll(String value, String separator, InterpreterOps ops) {
        final var result = new JsArray();
        if (separator.isEmpty()) {
            InterpreterOps.chargeElements(ops, value.length());
            for (var i = 0; i < value.length(); i++) {
                result.push(new JsString(String.valueOf(value.charAt(i))));
            }
            return result;
        }
        var from = 0;
        var index = value.indexOf(separator);
        while (index >= 0) {
            result.push(new JsString(value.substring(from, index)));
            from = index + separator.length();
            index = value.indexOf(separator, from);
        }
        result.push(new JsString(value.substring(from)));
        return result;
    }

    public static JsValue limited(JsArray array, long limit) {
        if (limit >= array.length()) {
            return array;
        }
        return new JsArray(List.copyOf(array.getElements().subList(0, (int) limit)));
    }

    public static JsValue replace(String value, List<JsValue> args, Invoker invoker, boolean all, InterpreterOps ops) {
        final var search = str(args, 0, ops);
        final var callable = args.size() > 1 && isCallable(args.get(1));
        final var replacement = callable ? null : str(args, 1, ops);
        if (all) {
            return new JsString(replaceAllLiteral(value, search, args, invoker, callable, replacement, ops));
        }
        final var index = value.indexOf(search);
        if (index < 0) {
            return new JsString(value);
        }
        final var piece = literalPiece(value, search, index, args, invoker, callable, replacement, ops);
        return new JsString(value.substring(0, index) + piece + value.substring(index + search.length()));
    }

    public static String literalPiece(String value, String search, int index, List<JsValue> args, Invoker invoker,
            boolean callable, String replacement, InterpreterOps ops) {
        if (callable) {
            return JsCoercion.toStr(invoker.call(args.get(1), JsUndefined.getInstance(),
                    List.of(new JsString(search), new JsNumber(index), new JsString(value))), ops);
        }
        return expandLiteral(replacement, search, value, index);
    }

    public static String expandLiteral(String template, String matched, String input, int position) {
        final var sb = new StringBuilder();
        for (var i = 0; i < template.length(); i++) {
            final var ch = template.charAt(i);
            if (ch != '$' || i + 1 >= template.length()) {
                sb.append(ch);
                continue;
            }
            switch (template.charAt(i + 1)) {
                case '$' -> sb.append('$');
                case '&' -> sb.append(matched);
                case '`' -> sb.append(input, 0, position);
                case '\'' -> sb.append(input.substring(position + matched.length()));
                default -> {
                    sb.append(ch);
                    continue;
                }
            }
            i++;
        }
        return sb.toString();
    }

    public static String replaceAllLiteral(String value, String search, List<JsValue> args, Invoker invoker,
            boolean callable, String replacement, InterpreterOps ops) {
        final var advanceBy = Math.max(1, search.length());
        final var sb = new StringBuilder();
        var from = 0;
        var index = value.indexOf(search);
        while (index >= 0) {
            sb.append(value, from, index);
            final var piece = literalPiece(value, search, index, args, invoker, callable, replacement, ops);
            InterpreterOps.chargeChars(ops, (long) piece.length() + index - from);
            sb.append(piece);
            from = index + search.length();
            final var next = index + advanceBy;
            if (next > value.length()) {
                break;
            }
            index = value.indexOf(search, next);
        }
        sb.append(value.substring(Math.min(from, value.length())));
        InterpreterOps.chargeChars(ops, sb.length());
        return sb.toString();
    }

    private StringRegExpDelegation() {
    }
}
