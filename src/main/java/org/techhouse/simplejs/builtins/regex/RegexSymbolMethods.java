package org.techhouse.simplejs.builtins.regex;

import static org.techhouse.simplejs.builtins.RegexBuiltins.ITERATOR_STATE;
import static org.techhouse.simplejs.builtins.regex.RegexExec.readLastIndex;
import static org.techhouse.simplejs.builtins.regex.RegexExec.regExpExec;
import static org.techhouse.simplejs.builtins.regex.RegexExec.setOrThrow;
import static org.techhouse.simplejs.builtins.regex.RegexExec.toLength;
import static org.techhouse.simplejs.builtins.regex.RegexExec.writeLastIndex;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.regex.RegexFlags.LAST_INDEX;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.SameValueZero;

public final class RegexSymbolMethods {
    public static JsValue symbolMatchAll(JsValue rx, String s, JsObject iteratorProto, JsObject regexpProto,
            InterpreterOps ops) {
        if (!InterpreterUtils.isObjectLike(rx)) {
            throw new TypeErrorException("RegExp.prototype[Symbol.matchAll] called on a non-object");
        }
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var species = speciesConstructor(rx, regexpProto, ops);
        final var matcher = species == null
                ? RegexTranslator.compile(sourceOf(rx, ops), flags)
                : ops.construct(species, List.of(rx, new JsString(flags)));
        writeLastIndex(matcher, (int) toLength(readLastIndex(rx, ops), ops), ops);
        return createStringIterator(matcher, s, flags.indexOf('g') >= 0,
                flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0, iteratorProto);
    }

    public static String sourceOf(JsValue rx, InterpreterOps ops) {
        return rx instanceof JsRegExp regexp
                ? regexp.getSource()
                : JsCoercion.toStr(ops.getMember(rx, new JsString("source")), ops);
    }

    public static JsValue speciesConstructor(JsValue rx, JsObject regexpProto, InterpreterOps ops) {
        final var constructor = ops.getMember(rx, new JsString("constructor"));
        if (constructor instanceof JsUndefined) {
            return defaultConstructor(regexpProto, ops);
        }
        if (!InterpreterUtils.isObjectLike(constructor)) {
            throw new TypeErrorException("constructor is not an object");
        }
        final var species = ops.getMember(constructor, JsSymbol.SPECIES);
        if (species instanceof JsUndefined || species instanceof JsNull) {
            return defaultConstructor(regexpProto, ops);
        }
        if (!InterpreterUtils.isConstructor(species)) {
            throw new TypeErrorException("Symbol.species is not a constructor");
        }
        return species;
    }

    public static JsValue defaultConstructor(JsObject regexpProto, InterpreterOps ops) {
        final var constructor = ops.getMember(regexpProto, new JsString("constructor"));
        return InterpreterUtils.isConstructor(constructor) ? constructor : null;
    }

    public static JsObject createStringIterator(JsValue matcher, String s, boolean global, boolean fullUnicode,
            JsObject proto) {
        final var iterator = new JsObject();
        iterator.setProto(proto);
        final var state = new JsObject();
        state.set("regexp", matcher);
        state.set("string", new JsString(s));
        state.set("global", JsBoolean.of(global));
        state.set("unicode", JsBoolean.of(fullUnicode));
        state.set("done", JsBoolean.FALSE);
        iterator.setSymbol(ITERATOR_STATE, state);
        return iterator;
    }

    public static JsValue stringIteratorNext(JsValue thisArg, InterpreterOps ops) {
        if (!(thisArg instanceof JsObject self) || !(self.getSymbol(ITERATOR_STATE) instanceof JsObject state)) {
            throw new TypeErrorException("next called on an incompatible receiver");
        }
        if (JsCoercion.toBoolean(state.get("done"))) {
            return iterationResult(JsUndefined.getInstance(), true);
        }
        final var rx = state.get("regexp");
        final var input = ((JsString) state.get("string")).getValue();
        final var match = regExpExec(rx, input, ops);
        if (match instanceof JsNull) {
            state.set("done", JsBoolean.TRUE);
            return iterationResult(JsUndefined.getInstance(), true);
        }
        if (!JsCoercion.toBoolean(state.get("global"))) {
            state.set("done", JsBoolean.TRUE);
            return iterationResult(match, false);
        }
        if (JsCoercion.toStr(ops.getMember(match, new JsString("0")), ops).isEmpty()) {
            final var index = toLength(readLastIndex(rx, ops), ops);
            setOrThrow(rx, new JsNumber(advanceStringIndex(input, index, JsCoercion.toBoolean(state.get("unicode")))),
                    ops);
        }
        return iterationResult(match, false);
    }

    public static JsObject iterationResult(JsValue value, boolean done) {
        final var result = new JsObject();
        result.set("value", value);
        result.set("done", JsBoolean.of(done));
        return result;
    }

    public static double advanceStringIndex(String s, double index, boolean unicode) {
        if (!unicode || index + 1 >= s.length()) {
            return index + 1;
        }
        final var at = (int) index;
        return Character.isHighSurrogate(s.charAt(at)) && Character.isLowSurrogate(s.charAt(at + 1))
                ? index + 2
                : index + 1;
    }

    public static JsValue symbolMatch(JsValue rx, String s, InterpreterOps ops) {
        requireObject(rx, "Symbol.match");
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        if (flags.indexOf('g') < 0) {
            return regExpExec(rx, s, ops);
        }
        final var fullUnicode = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
        writeLastIndex(rx, 0, ops);
        final var result = new JsArray();
        while (true) {
            final var match = regExpExec(rx, s, ops);
            if (match instanceof JsNull) {
                return result.length() == 0 ? JsNull.getInstance() : result;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(match, new JsString("0")), ops);
            InterpreterOps.chargeChars(ops, matchStr.length() + InterpreterOps.BYTES_PER_ELEMENT);
            result.push(new JsString(matchStr));
            if (matchStr.isEmpty()) {
                final var lastIndex = toLength(readLastIndex(rx, ops), ops);
                setOrThrow(rx, new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)), ops);
            }
        }
    }

    public static void requireObject(JsValue rx, String method) {
        if (!InterpreterUtils.isObjectLike(rx)) {
            throw new TypeErrorException("RegExp.prototype[" + method + "] called on a non-object");
        }
    }

    public static JsValue symbolSearch(JsValue rx, String s, InterpreterOps ops) {
        final var previousLastIndex = ops.getMember(rx, new JsString(LAST_INDEX));
        if (isNotSameValue(previousLastIndex, new JsNumber(0))) {
            setOrThrow(rx, new JsNumber(0), ops);
        }
        final var result = regExpExec(rx, s, ops);
        final var currentLastIndex = ops.getMember(rx, new JsString(LAST_INDEX));
        if (isNotSameValue(currentLastIndex, previousLastIndex)) {
            setOrThrow(rx, previousLastIndex, ops);
        }
        return result instanceof JsNull ? new JsNumber(-1) : ops.getMember(result, new JsString("index"));
    }

    public static boolean isNotSameValue(JsValue left, JsValue right) {
        if (left instanceof JsNumber first && right instanceof JsNumber second) {
            return Double.compare(first.getValue(), second.getValue()) != 0;
        }
        return !SameValueZero.equal(left, right);
    }

    public static JsValue symbolReplace(JsValue rx, String s, JsValue replaceValue, InterpreterOps ops,
            Invoker invoker) {
        requireObject(rx, "Symbol.replace");
        final var functionalReplace = isCallable(replaceValue);
        final var replacementTemplate = functionalReplace ? null : JsCoercion.toStr(replaceValue, ops);
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var global = flags.indexOf('g') >= 0;
        final var fullUnicode = global && (flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0);
        if (global) {
            writeLastIndex(rx, 0, ops);
        }
        final var results = new ArrayList<JsValue>();
        while (true) {
            final var result = regExpExec(rx, s, ops);
            if (result instanceof JsNull) {
                break;
            }
            InterpreterOps.chargeElements(ops, 1);
            results.add(result);
            if (!global) {
                break;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(result, new JsString("0")), ops);
            if (matchStr.isEmpty()) {
                final var lastIndex = toLength(readLastIndex(rx, ops), ops);
                setOrThrow(rx, new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)), ops);
            }
        }
        final var accumulated = new StringBuilder();
        var chargedChars = 0;
        var nextSourcePosition = 0;
        for (final var result : results) {
            final var length = (int) JsCoercion.toNumber(ops.getMember(result, new JsString("length")), ops);
            final var nCaptures = Math.max(length - 1, 0);
            final var matched = JsCoercion.toStr(ops.getMember(result, new JsString("0")), ops);
            final var position = Math.clamp(
                    (long) JsCoercion.toNumber(ops.getMember(result, new JsString("index")), ops), 0, s.length());
            final var captures = new ArrayList<JsValue>();
            for (var n = 1; n <= nCaptures; n++) {
                final var capture = ops.getMember(result, new JsString(String.valueOf(n)));
                captures.add(capture instanceof JsUndefined ? capture : new JsString(JsCoercion.toStr(capture, ops)));
            }
            final var namedCaptures = ops.getMember(result, new JsString("groups"));
            final String replacement;
            if (functionalReplace) {
                final var replacerArgs = new ArrayList<JsValue>();
                replacerArgs.add(new JsString(matched));
                replacerArgs.addAll(captures);
                replacerArgs.add(new JsNumber(position));
                replacerArgs.add(new JsString(s));
                if (!(namedCaptures instanceof JsUndefined)) {
                    replacerArgs.add(namedCaptures);
                }
                replacement = JsCoercion.toStr(invoker.call(replaceValue, JsUndefined.getInstance(), replacerArgs),
                        ops);
            } else {
                if (namedCaptures instanceof JsNull) {
                    throw new TypeErrorException("Cannot convert the named-capture groups to an object");
                }
                replacement = getSubstitution(matched, s, position, captures, namedCaptures, replacementTemplate, ops);
            }
            if (position >= nextSourcePosition) {
                accumulated.append(s, nextSourcePosition, position).append(replacement);
                InterpreterOps.chargeChars(ops, accumulated.length() - chargedChars);
                chargedChars = accumulated.length();
                nextSourcePosition = position + matched.length();
            }
        }
        if (nextSourcePosition < s.length()) {
            accumulated.append(s, nextSourcePosition, s.length());
        }
        return new JsString(accumulated.toString());
    }

    public static String getSubstitution(String matched, String s, int position, List<JsValue> captures,
            JsValue namedCaptures, String template, InterpreterOps ops) {
        final var sb = new StringBuilder();
        for (var i = 0; i < template.length(); i++) {
            final var ch = template.charAt(i);
            if (ch != '$' || i + 1 >= template.length()) {
                sb.append(ch);
                continue;
            }
            final var next = template.charAt(i + 1);
            switch (next) {
                case '$' -> {
                    sb.append('$');
                    i++;
                }
                case '&' -> {
                    sb.append(matched);
                    i++;
                }
                case '`' -> {
                    sb.append(s, 0, position);
                    i++;
                }
                case '\'' -> {
                    sb.append(s.substring(position + matched.length()));
                    i++;
                }
                case '<' -> {
                    final var close = template.indexOf('>', i + 2);
                    if (close < 0 || namedCaptures instanceof JsUndefined) {
                        sb.append(ch);
                    } else {
                        final var name = template.substring(i + 2, close);
                        final var value = ops.getMember(namedCaptures, new JsString(name));
                        if (!(value instanceof JsUndefined)) {
                            sb.append(JsCoercion.toStr(value, ops));
                        }
                        i = close;
                    }
                }
                default -> {
                    if (Character.isDigit(next)) {
                        i = appendCaptureGroup(sb, template, i, captures) - 1;
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }

    public static int appendCaptureGroup(StringBuilder sb, String template, int dollarIndex, List<JsValue> captures) {
        var end = dollarIndex + 2;
        if (end < template.length() && Character.isDigit(template.charAt(end))
                && Integer.parseInt(template.substring(dollarIndex + 1, end + 1)) <= captures.size()) {
            end++;
        }
        final var group = Integer.parseInt(template.substring(dollarIndex + 1, end));
        if (group >= 1 && group <= captures.size()) {
            final var value = captures.get(group - 1);
            if (!(value instanceof JsUndefined)) {
                sb.append(((JsString) value).getValue());
            }
            return end;
        }
        sb.append(template, dollarIndex, dollarIndex + 1);
        return dollarIndex + 1;
    }

    public static JsValue symbolSplit(JsValue rx, String s, JsValue limitValue, JsObject regexpProto,
            InterpreterOps ops) {
        requireObject(rx, "Symbol.split");
        final var species = speciesConstructor(rx, regexpProto, ops);
        final var flags = JsCoercion.toStr(ops.getMember(rx, new JsString("flags")), ops);
        final var unicodeMatching = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
        final var newFlags = flags.indexOf('y') >= 0 ? flags : flags + "y";
        final var splitter = species == null
                ? RegexTranslator.compile(sourceOf(rx, ops), newFlags)
                : ops.construct(species, List.of(rx, new JsString(newFlags)));
        final var result = new JsArray();
        final var limit = limitValue instanceof JsUndefined
                ? 0xFFFFFFFFL
                : ((long) JsCoercion.toNumber(limitValue, ops)) & 0xFFFFFFFFL;
        if (limit == 0) {
            return result;
        }
        final var length = s.length();
        if (length == 0) {
            if (!(regExpExec(splitter, s, ops) instanceof JsNull)) {
                return result;
            }
            result.push(new JsString(s));
            return result;
        }
        var p = 0;
        var q = 0;
        while (q < length) {
            writeLastIndex(splitter, q, ops);
            final var z = regExpExec(splitter, s, ops);
            if (z instanceof JsNull) {
                q = (int) advanceStringIndex(s, q, unicodeMatching);
                continue;
            }
            final var e = Math.min((int) JsCoercion.toNumber(ops.getMember(splitter, new JsString(LAST_INDEX)), ops),
                    length);
            if (e == p) {
                q = (int) advanceStringIndex(s, q, unicodeMatching);
                continue;
            }
            result.push(new JsString(s.substring(p, q)));
            if (result.length() == limit) {
                return result;
            }
            final var groupCount = (int) JsCoercion.toNumber(ops.getMember(z, new JsString("length")), ops) - 1;
            for (var i = 1; i <= groupCount; i++) {
                result.push(ops.getMember(z, new JsString(String.valueOf(i))));
                if (result.length() == limit) {
                    return result;
                }
            }
            p = e;
            q = p;
        }
        result.push(new JsString(s.substring(p, length)));
        return result;
    }

    private RegexSymbolMethods() {
    }
}
