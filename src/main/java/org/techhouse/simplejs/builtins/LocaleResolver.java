package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class LocaleResolver {
    private static final Set<String> SENSITIVITIES = Set.of("base", "accent", "case", "variant");
    private static final Set<String> USAGES = Set.of("sort", "search");
    private static final Set<String> CASE_FIRST = Set.of("upper", "lower", "false");

    private LocaleResolver() {
    }

    public static Locale resolve(List<JsValue> args, int index, InterpreterOps ops) {
        final var requested = arg(args, index);
        if (requested instanceof JsUndefined || requested == null) {
            return InterpreterOps.locale(ops);
        }
        final var tag = firstTag(requested, ops);
        return tag == null ? InterpreterOps.locale(ops) : toLocale(tag);
    }

    public static String option(List<JsValue> args, int index, String key, Set<String> allowed, InterpreterOps ops) {
        final var options = arg(args, index);
        if (options == null || options instanceof JsUndefined) {
            return null;
        }
        if (!(options instanceof JsObject object)) {
            throw new TypeErrorException("Options must be an object");
        }
        final var value = object.get(key);
        if (value == null || value instanceof JsUndefined) {
            return null;
        }
        final var text = JsCoercion.toStr(value, ops);
        if (!allowed.contains(text)) {
            throw new RangeErrorException("Value " + text + " out of range for options property " + key);
        }
        return text;
    }

    public static String sensitivity(List<JsValue> args, int index, InterpreterOps ops) {
        option(args, index, "usage", USAGES, ops);
        option(args, index, "caseFirst", CASE_FIRST, ops);
        return option(args, index, "sensitivity", SENSITIVITIES, ops);
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return args != null && args.size() > index ? args.get(index) : null;
    }

    private static String firstTag(JsValue requested, InterpreterOps ops) {
        if (requested instanceof JsArray array) {
            for (final var element : array.getElements()) {
                if (element != null && !(element instanceof JsUndefined)) {
                    return JsCoercion.toStr(element, ops);
                }
            }
            return null;
        }
        return JsCoercion.toStr(requested, ops);
    }

    private static Locale toLocale(String tag) {
        final var locale = Locale.forLanguageTag(tag);
        if (locale.getLanguage().isEmpty()) {
            throw new RangeErrorException("Incorrect locale information provided");
        }
        return locale;
    }
}
