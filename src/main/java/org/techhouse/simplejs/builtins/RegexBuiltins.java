package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class RegexBuiltins {
    public static final List<String> NAMES = List.of("test", "exec");
    private static final Set<String> ACCESSORS = Set.of("source", "flags", "global", "ignoreCase", "multiline",
            "dotAll", "sticky", "lastIndex", "hasIndices", "unicode", "unicodeSets");

    private static final String SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|/";
    private static final String OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\"";
    private static final char VERTICAL_TAB = '\u000B';
    private static final char LINE_SEPARATOR = '\u2028';
    private static final char PARAGRAPH_SEPARATOR = '\u2029';

    private RegexBuiltins() {
    }

    public static JsNativeFunction create() {
        final var regExp = new JsNativeFunction("RegExp", (_, args) -> construct(args));
        regExp.setProperty("escape", new JsNativeFunction("escape", (_, args) -> new JsString(escape(args))));
        return regExp;
    }

    private static String escape(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsString first)) {
            throw new TypeErrorException("RegExp.escape argument must be a string");
        }
        final var value = first.getValue();
        final var result = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var ch = value.charAt(i);
            if (i == 0 && isAlphanumeric(ch)) {
                appendHex(result, ch);
            } else if (SYNTAX_CHARACTERS.indexOf(ch) >= 0) {
                result.append('\\').append(ch);
            } else {
                appendNamedOrLiteral(result, ch);
            }
        }
        return result.toString();
    }

    private static void appendNamedOrLiteral(StringBuilder result, char ch) {
        switch (ch) {
            case '\t' -> result.append("\\t");
            case '\n' -> result.append("\\n");
            case VERTICAL_TAB -> result.append("\\v");
            case '\f' -> result.append("\\f");
            case '\r' -> result.append("\\r");
            default -> {
                if (OTHER_PUNCTUATORS.indexOf(ch) >= 0 || Character.isWhitespace(ch) || isLineTerminator(ch)) {
                    appendHex(result, ch);
                } else {
                    result.append(ch);
                }
            }
        }
    }

    private static boolean isAlphanumeric(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
    }

    private static boolean isLineTerminator(char ch) {
        return ch == LINE_SEPARATOR || ch == PARAGRAPH_SEPARATOR;
    }

    private static void appendHex(StringBuilder result, char ch) {
        if (ch <= 0xFF) {
            result.append("\\x").append(String.format("%02x", (int) ch));
        } else {
            result.append("\\u").append(String.format("%04x", (int) ch));
        }
    }

    private static JsValue construct(List<JsValue> args) {
        final var first = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var explicitFlags = args.size() > 1 && !(args.get(1) instanceof JsUndefined);
        if (first instanceof JsRegExp existing) {
            final var flags = explicitFlags ? JsCoercion.toStr(args.get(1)) : existing.getFlags();
            return RegexTranslator.compile(existing.getSource(), flags);
        }
        final var source = first instanceof JsUndefined ? "" : JsCoercion.toStr(first);
        final var flags = explicitFlags ? JsCoercion.toStr(args.get(1)) : "";
        return RegexTranslator.compile(source, flags);
    }

    public static boolean isAccessor(String name) {
        return ACCESSORS.contains(name);
    }

    public static JsValue getMethod(JsRegExp receiver, String name) {
        return switch (name) {
            case "test" -> new JsNativeFunction("test", (_, args) -> JsBoolean.of(test(receiver, str(args))));
            case "exec" -> new JsNativeFunction("exec", (_, args) -> exec(receiver, str(args)));
            case "source" -> new JsString(receiver.getSource());
            case "flags" -> new JsString(receiver.getFlags());
            case "global" -> JsBoolean.of(receiver.isGlobal());
            case "ignoreCase" -> JsBoolean.of(receiver.isIgnoreCase());
            case "multiline" -> JsBoolean.of(receiver.isMultiline());
            case "dotAll" -> JsBoolean.of(receiver.isDotAll());
            case "sticky" -> JsBoolean.of(receiver.isSticky());
            case "hasIndices" -> JsBoolean.of(receiver.hasIndices());
            case "unicode" -> JsBoolean.of(receiver.isUnicode());
            case "unicodeSets" -> JsBoolean.of(receiver.isUnicodeSets());
            case "lastIndex" -> new JsNumber(receiver.getLastIndex());
            default -> null;
        };
    }

    public static boolean test(JsRegExp regexp, String input) {
        return !(exec(regexp, input) instanceof JsNull);
    }

    public static JsValue exec(JsRegExp regexp, String input) {
        final var stateful = regexp.isGlobal() || regexp.isSticky();
        final var start = stateful ? regexp.getLastIndex() : 0;
        if (start < 0 || start > input.length()) {
            regexp.setLastIndex(0);
            return JsNull.getInstance();
        }
        final var matcher = regexp.getPattern().matcher(input);
        final var found = regexp.isSticky() ? matcher.find(start) && matcher.start() == start : matcher.find(start);
        if (!found) {
            if (stateful) {
                regexp.setLastIndex(0);
            }
            return JsNull.getInstance();
        }
        if (stateful) {
            regexp.setLastIndex(matcher.end());
        }
        final var result = buildMatchResult(matcher, input, regexp);
        if (regexp.hasIndices()) {
            addIndices(result, matcher, regexp);
        }
        return result;
    }

    public static void addIndices(JsArray result, Matcher matcher, JsRegExp regexp) {
        final var indices = new JsArray();
        for (var i = 0; i <= matcher.groupCount(); i++) {
            indices.push(pair(matcher.start(i), matcher.end(i)));
        }
        final var names = groupNames(regexp);
        if (names.isEmpty()) {
            indices.setProperty("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                final var alias = participatingGroup(regexp, groupName, matcher);
                groups.set(groupName,
                        alias == null ? JsUndefined.getInstance() : pair(matcher.start(alias), matcher.end(alias)));
            }
            indices.setProperty("groups", groups);
        }
        result.setProperty("indices", indices);
    }

    private static JsValue pair(int start, int end) {
        if (start < 0) {
            return JsUndefined.getInstance();
        }
        return new JsArray(List.of(new JsNumber(start), new JsNumber(end)));
    }

    public static JsArray buildMatchResult(Matcher matcher, String input, JsRegExp regexp) {
        final var result = new JsArray();
        final var count = matcher.groupCount();
        for (var i = 0; i <= count; i++) {
            result.push(groupValue(matcher.group(i)));
        }
        result.setProperty("index", new JsNumber(matcher.start()));
        result.setProperty("input", new JsString(input));
        final var names = groupNames(regexp);
        if (names.isEmpty()) {
            result.setProperty("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                final var alias = participatingGroup(regexp, groupName, matcher);
                groups.set(groupName, alias == null ? JsUndefined.getInstance() : groupValue(matcher.group(alias)));
            }
            result.setProperty("groups", groups);
        }
        return result;
    }

    public static List<String> groupNames(JsRegExp regexp) {
        return List.copyOf(regexp.getGroupAliases().keySet());
    }

    // A duplicated name compiles to several java groups; at most one of them can have participated.
    public static String participatingGroup(JsRegExp regexp, String name, Matcher matcher) {
        final var aliases = regexp.getGroupAliases().get(name);
        if (aliases == null) {
            return name;
        }
        for (final var alias : aliases) {
            if (matcher.start(alias) >= 0) {
                return alias;
            }
        }
        return null;
    }

    private static JsValue groupValue(String value) {
        return value == null ? JsUndefined.getInstance() : new JsString(value);
    }

    private static String str(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    // Spec RegExpExec(R, S): dispatch through a (possibly user-overridden) "exec" own/inherited
    // property rather than matching internally, so overriding `exec` on a real JsRegExp changes the
    // behaviour of match/replace/search/split, matching the abstract operation's generality.
    public static JsValue regExpExec(JsValue rx, String s, InterpreterOps ops) {
        final var execFn = ops.getMember(rx, new JsString("exec"));
        if (isCallable(execFn)) {
            final var result = ops.call(execFn, rx, List.of(new JsString(s)));
            if (!(result instanceof JsObject) && !(result instanceof JsArray) && !(result instanceof JsNull)) {
                throw new TypeErrorException("RegExp exec method returned something other than an object or null");
            }
            return result;
        }
        if (rx instanceof JsRegExp regexp) {
            return exec(regexp, s);
        }
        throw new TypeErrorException("RegExp.prototype.exec method is not generic");
    }

    private static int advanceStringIndex(String s, int index, boolean unicode) {
        if (!unicode || index + 1 >= s.length()) {
            return index + 1;
        }
        return Character.isHighSurrogate(s.charAt(index)) && Character.isLowSurrogate(s.charAt(index + 1))
                ? index + 2
                : index + 1;
    }

    public static JsValue symbolMatch(JsValue rx, String s, InterpreterOps ops) {
        final var global = JsCoercion.toBoolean(ops.getMember(rx, new JsString("global")));
        if (!global) {
            return regExpExec(rx, s, ops);
        }
        final var fullUnicode = JsCoercion.toBoolean(ops.getMember(rx, new JsString("unicode")));
        ops.setMember(rx, new JsString("lastIndex"), new JsNumber(0));
        final var result = new JsArray();
        while (true) {
            final var match = regExpExec(rx, s, ops);
            if (match instanceof JsNull) {
                return result.length() == 0 ? JsNull.getInstance() : result;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(match, new JsString("0")), ops);
            result.push(new JsString(matchStr));
            if (matchStr.isEmpty()) {
                final var lastIndex = (int) JsCoercion.toNumber(ops.getMember(rx, new JsString("lastIndex")), ops);
                ops.setMember(rx, new JsString("lastIndex"),
                        new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)));
            }
        }
    }

    public static JsValue symbolSearch(JsValue rx, String s, InterpreterOps ops) {
        final var previousLastIndex = ops.getMember(rx, new JsString("lastIndex"));
        if (JsCoercion.toNumber(previousLastIndex, ops) != 0) {
            ops.setMember(rx, new JsString("lastIndex"), new JsNumber(0));
        }
        final var result = regExpExec(rx, s, ops);
        final var currentLastIndex = ops.getMember(rx, new JsString("lastIndex"));
        if (JsCoercion.toNumber(currentLastIndex, ops) != JsCoercion.toNumber(previousLastIndex, ops)) {
            ops.setMember(rx, new JsString("lastIndex"), previousLastIndex);
        }
        return result instanceof JsNull ? new JsNumber(-1) : ops.getMember(result, new JsString("index"));
    }

    public static JsValue symbolReplace(JsValue rx, String s, JsValue replaceValue, InterpreterOps ops,
            Invoker invoker) {
        final var functionalReplace = isCallable(replaceValue);
        final var replacementTemplate = functionalReplace ? null : JsCoercion.toStr(replaceValue, ops);
        final var global = JsCoercion.toBoolean(ops.getMember(rx, new JsString("global")));
        final var fullUnicode = global && JsCoercion.toBoolean(ops.getMember(rx, new JsString("unicode")));
        if (global) {
            ops.setMember(rx, new JsString("lastIndex"), new JsNumber(0));
        }
        final var results = new ArrayList<JsValue>();
        while (true) {
            final var result = regExpExec(rx, s, ops);
            if (result instanceof JsNull) {
                break;
            }
            results.add(result);
            if (!global) {
                break;
            }
            final var matchStr = JsCoercion.toStr(ops.getMember(result, new JsString("0")), ops);
            if (matchStr.isEmpty()) {
                final var lastIndex = (int) JsCoercion.toNumber(ops.getMember(rx, new JsString("lastIndex")), ops);
                ops.setMember(rx, new JsString("lastIndex"),
                        new JsNumber(advanceStringIndex(s, lastIndex, fullUnicode)));
            }
        }
        final var accumulated = new StringBuilder();
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
                replacement = JsCoercion.toStr(invoker.call(replaceValue, JsUndefined.getInstance(), replacerArgs));
            } else {
                replacement = getSubstitution(matched, s, position, captures, namedCaptures, replacementTemplate,
                        ops);
            }
            if (position >= nextSourcePosition) {
                accumulated.append(s, nextSourcePosition, position).append(replacement);
                nextSourcePosition = position + matched.length();
            }
        }
        if (nextSourcePosition < s.length()) {
            accumulated.append(s, nextSourcePosition, s.length());
        }
        return new JsString(accumulated.toString());
    }

    private static String getSubstitution(String matched, String s, int position, List<JsValue> captures,
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
                        i = close - 1;
                    }
                    i++;
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

    private static int appendCaptureGroup(StringBuilder sb, String template, int dollarIndex, List<JsValue> captures) {
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

    public static JsValue symbolSplit(JsValue rx, String s, JsValue limitValue, InterpreterOps ops) {
        if (!(rx instanceof JsRegExp regexp)) {
            throw new TypeErrorException("RegExp.prototype[Symbol.split] method is not generic");
        }
        final var flags = regexp.getFlags();
        final var unicodeMatching = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0;
        final var newFlags = flags.indexOf('y') >= 0 ? flags : flags + "y";
        final var splitter = RegexTranslator.compile(regexp.getSource(), newFlags);
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
            splitter.setLastIndex(q);
            final var z = regExpExec(splitter, s, ops);
            if (z instanceof JsNull) {
                q = advanceStringIndex(s, q, unicodeMatching);
                continue;
            }
            final var e = Math.min((int) JsCoercion.toNumber(ops.getMember(splitter, new JsString("lastIndex")), ops),
                    length);
            if (e == p) {
                q = advanceStringIndex(s, q, unicodeMatching);
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
}
