package org.techhouse.simplejs.builtins;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private static final Map<String, Locale> AVAILABLE = Stream.of(Locale.getAvailableLocales())
            .collect(Collectors.toUnmodifiableMap(Locale::toLanguageTag, Function.identity(), (first, _) -> first));
    private static final int SCRIPT_LENGTH = 4;
    private static final int ALPHA_REGION_LENGTH = 2;
    private static final int NUMERIC_REGION_LENGTH = 3;
    private static final String INCORRECT_LOCALE = "Incorrect locale information provided";

    private LocaleResolver() {
    }

    public static Locale resolve(List<JsValue> args, int index, InterpreterOps ops) {
        final var requested = arg(args, index);
        if (requested instanceof JsUndefined || requested == null) {
            return InterpreterOps.locale(ops);
        }
        final var tag = firstTag(requested, ops);
        return tag == null ? InterpreterOps.locale(ops) : toLocale(tag, ops);
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

    private static Locale toLocale(String tag, InterpreterOps ops) {
        final var match = Locale.lookupTag(List.of(new Locale.LanguageRange(baseRange(tag))), AVAILABLE.keySet());
        return match == null ? InterpreterOps.locale(ops) : AVAILABLE.get(match);
    }

    private static String baseRange(String tag) {
        try {
            //noinspection WriteOnlyObject
            new Locale.Builder().setLanguageTag(tag);
        } catch (IllformedLocaleException e) {
            throw new RangeErrorException(INCORRECT_LOCALE);
        }
        final var subtags = tag.split("-");
        final var language = subtags[0].toLowerCase(Locale.ROOT);
        if (!isLanguage(language)) {
            throw new RangeErrorException(INCORRECT_LOCALE);
        }
        final var range = new StringBuilder(language);
        var index = 1;
        while (index < subtags.length && isExtlang(subtags[index])) {
            index++;
        }
        if (index < subtags.length && isScript(subtags[index])) {
            final var script = subtags[index].toLowerCase(Locale.ROOT);
            range.append('-').append(Character.toUpperCase(script.charAt(0))).append(script, 1, SCRIPT_LENGTH);
            index++;
        }
        if (index < subtags.length && isRegion(subtags[index])) {
            range.append('-').append(subtags[index].toUpperCase(Locale.ROOT));
        }
        return range.toString();
    }

    private static boolean isLanguage(String subtag) {
        final var length = subtag.length();
        return isAlpha(subtag) && (length == 2 || length == 3 || length >= 5) && !"und".equals(subtag);
    }

    private static boolean isExtlang(String subtag) {
        return subtag.length() == NUMERIC_REGION_LENGTH && isAlpha(subtag);
    }

    private static boolean isScript(String subtag) {
        return subtag.length() == SCRIPT_LENGTH && isAlpha(subtag);
    }

    private static boolean isRegion(String subtag) {
        return subtag.length() == ALPHA_REGION_LENGTH && isAlpha(subtag)
                || subtag.length() == NUMERIC_REGION_LENGTH && subtag.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static boolean isAlpha(String subtag) {
        return subtag.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z');
    }
}
