package org.techhouse.simplejs.builtins.regex;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.isCallable;
import static org.techhouse.simplejs.internal.regex.RegexFlags.LAST_INDEX;
import static org.techhouse.simplejs.values.JsLimits.MAX_SAFE_INTEGER;

import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.regex.RegexMatch;
import org.techhouse.simplejs.internal.regex.RegexMatcher;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class RegexExec {
    public static JsValue builtinExec(JsValue target, JsRegExp state, String input, InterpreterOps ops) {
        final var global = state.isGlobal();
        final var sticky = state.isSticky();
        final var stateful = global || sticky;
        final var read = toLength(readLastIndex(target, ops), ops);
        final var lastIndex = stateful ? read : 0;
        if (lastIndex > input.length()) {
            resetLastIndex(target, stateful, ops);
            return JsNull.getInstance();
        }
        final var start = (int) lastIndex;
        final var matcher = RegexMatcher.exec(state.getProgram(), input, start, sticky);
        if (matcher == null) {
            resetLastIndex(target, stateful, ops);
            return JsNull.getInstance();
        }
        if (stateful) {
            writeLastIndex(target, matcher.end(), ops);
        }
        final var result = buildMatchResult(matcher, input, state);
        if (state.hasIndices()) {
            addIndices(result, matcher, state);
        }
        return result;
    }

    public static JsValue readLastIndex(JsValue target, InterpreterOps ops) {
        if (ops == null) {
            return target instanceof JsRegExp regexp ? regexp.getLastIndex() : new JsNumber(0);
        }
        return ops.getMember(target, new JsString(LAST_INDEX));
    }

    public static void resetLastIndex(JsValue target, boolean stateful, InterpreterOps ops) {
        if (stateful) {
            writeLastIndex(target, 0, ops);
        }
    }

    public static void writeLastIndex(JsValue target, int value, InterpreterOps ops) {
        if (ops == null) {
            if (target instanceof JsRegExp regexp) {
                regexp.setLastIndex(value);
            }
            return;
        }
        setOrThrow(target, new JsNumber(value), ops);
    }

    public static void setOrThrow(JsValue target, JsValue value, InterpreterOps ops) {
        if (!ops.setMember(target, new JsString(LAST_INDEX), value)) {
            throw new TypeErrorException("Cannot assign to read only property 'lastIndex'");
        }
    }

    public static double toLength(JsValue value, InterpreterOps ops) {
        final var number = JsCoercion.toNumber(value, ops);
        if (Double.isNaN(number) || number <= 0) {
            return 0;
        }
        return Math.min(Math.floor(number), MAX_SAFE_INTEGER);
    }

    public static void addIndices(JsArray result, RegexMatch matcher, JsRegExp regexp) {
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

    public static JsValue pair(int start, int end) {
        if (start < 0) {
            return JsUndefined.getInstance();
        }
        return new JsArray(List.of(new JsNumber(start), new JsNumber(end)));
    }

    public static JsArray buildMatchResult(RegexMatch matcher, String input, JsRegExp regexp) {
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

    public static Integer participatingGroup(JsRegExp regexp, String name, RegexMatch matcher) {
        final var aliases = regexp.getGroupAliases().get(name);
        if (aliases == null) {
            return null;
        }
        for (final var alias : aliases) {
            if (matcher.start(alias) >= 0) {
                return alias;
            }
        }
        return null;
    }

    public static JsValue groupValue(String value) {
        return value == null ? JsUndefined.getInstance() : new JsString(value);
    }

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
            return builtinExec(rx, regexp, s, ops);
        }
        throw new TypeErrorException("RegExp.prototype.exec method is not generic");
    }

    private RegexExec() {
    }
}
