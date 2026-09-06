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

/**
 * Resolves the {@code locales} and {@code options} arguments the {@code toLocaleString}/{@code localeCompare}
 * family accepts.
 *
 * <p>
 * There is no {@code Intl} here, so this is deliberately the subset {@code java.text} can honour: a language
 * tag selects the formatter or collator, and of the collator options only {@code sensitivity} maps onto
 * anything ({@link java.text.Collator}'s strength). The rest are accepted and ignored rather than rejected,
 * because refusing a well-formed option a script may legitimately pass would be worse than approximating it -
 * but a *malformed* tag or option value is still an error, as the spec requires.
 */
public final class LocaleResolver {
    private static final Set<String> SENSITIVITIES = Set.of("base", "accent", "case", "variant");
    private static final Set<String> USAGES = Set.of("sort", "search");
    private static final Set<String> CASE_FIRST = Set.of("upper", "lower", "false");

    private LocaleResolver() {
    }

    /** The requested locale, or the host's when the argument is absent. */
    public static Locale resolve(List<JsValue> args, int index, InterpreterOps ops) {
        final var requested = arg(args, index);
        if (requested instanceof JsUndefined || requested == null) {
            return InterpreterOps.locale(ops);
        }
        final var tag = firstTag(requested, ops);
        return tag == null ? InterpreterOps.locale(ops) : toLocale(tag);
    }

    /**
     * Reads one enumerated option. A value outside {@code allowed} is a {@code RangeError} and a non-object
     * {@code options} a {@code TypeError}, both per spec; an absent option answers null.
     */
    public static String option(List<JsValue> args, int index, String key, Set<String> allowed, InterpreterOps ops) {
        final var options = arg(args, index);
        if (options == null || options instanceof JsUndefined) {
            return null;
        }
        // null lands here too, being a JsValue rather than a JsObject - which is the spec's answer for it.
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
        // Read for their validation only: java.text.Collator can express none of them, so honouring them
        // would need Intl. Validating anyway keeps a typo an error rather than a silent no-op.
        option(args, index, "usage", USAGES, ops);
        option(args, index, "caseFirst", CASE_FIRST, ops);
        return option(args, index, "sensitivity", SENSITIVITIES, ops);
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return args != null && args.size() > index ? args.get(index) : null;
    }

    // An array of tags picks the first well-formed one, which is what the spec's LookupSupportedLocales
    // does for an implementation supporting a single locale per request.
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
