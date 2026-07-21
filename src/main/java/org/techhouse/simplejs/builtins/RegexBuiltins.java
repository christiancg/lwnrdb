package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.RegexTranslator;
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
    private static final Pattern NAMED_GROUP = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    private RegexBuiltins() {
    }

    public static JsNativeFunction create() {
        return new JsNativeFunction("RegExp", (_, args) -> construct(args));
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
        return buildMatchResult(matcher, input, regexp.getSource());
    }

    public static JsObject buildMatchResult(Matcher matcher, String input, String source) {
        final var result = new JsObject();
        final var count = matcher.groupCount();
        for (var i = 0; i <= count; i++) {
            result.set(String.valueOf(i), groupValue(matcher.group(i)));
        }
        result.set("length", new JsNumber(count + 1));
        result.set("index", new JsNumber(matcher.start()));
        result.set("input", new JsString(input));
        final var names = groupNames(source);
        if (names.isEmpty()) {
            result.set("groups", JsUndefined.getInstance());
        } else {
            final var groups = new JsObject();
            for (final var groupName : names) {
                groups.set(groupName, groupValue(matcher.group(groupName)));
            }
            result.set("groups", groups);
        }
        return result;
    }

    public static List<String> groupNames(String source) {
        final var names = new ArrayList<String>();
        final var matcher = NAMED_GROUP.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static JsValue groupValue(String value) {
        return value == null ? JsUndefined.getInstance() : new JsString(value);
    }

    private static String str(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }
}
