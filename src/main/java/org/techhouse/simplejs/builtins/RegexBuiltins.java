package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
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
            "dotAll", "sticky", "lastIndex", "hasIndices");

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

    public static void addIndices(JsObject result, Matcher matcher, JsRegExp regexp) {
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
        result.set("indices", indices);
    }

    private static JsValue pair(int start, int end) {
        if (start < 0) {
            return JsUndefined.getInstance();
        }
        return new JsArray(List.of(new JsNumber(start), new JsNumber(end)));
    }

    public static JsObject buildMatchResult(Matcher matcher, String input, JsRegExp regexp) {
        final var result = new JsObject();
        final var count = matcher.groupCount();
        for (var i = 0; i <= count; i++) {
            result.set(String.valueOf(i), groupValue(matcher.group(i)));
        }
        result.set("length", new JsNumber(count + 1));
        result.set("index", new JsNumber(matcher.start()));
        result.set("input", new JsString(input));
        final var names = groupNames(regexp);
        if (names.isEmpty()) {
            result.set("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                final var alias = participatingGroup(regexp, groupName, matcher);
                groups.set(groupName, alias == null ? JsUndefined.getInstance() : groupValue(matcher.group(alias)));
            }
            result.set("groups", groups);
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
}
