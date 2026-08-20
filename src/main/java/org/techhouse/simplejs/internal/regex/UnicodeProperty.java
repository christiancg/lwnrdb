package org.techhouse.simplejs.internal.regex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

/**
 * Resolves an ECMA-262 {@code \p{...}}/{@code \P{...}} property name to the {@link CodePointSet} of
 * code points it accepts. {@code java.util.regex.Pattern} is used purely as a one-time, per-property
 * *oracle* here (compiled once, then every code point is tested against it and the truth table is
 * cached as a {@link CodePointSet}) - never as the actual matching engine, which is
 * {@link RegexMatcher}. This reuses the JDK's own Unicode property data (same version/behaviour the
 * codebase already depended on before this engine existed) without re-implementing it.
 */
final class UnicodeProperty {
    private static final Set<String> GENERAL_CATEGORY_CODES = Set.of("L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc",
            "Me", "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So",
            "Z", "Zs", "Zl", "Zp", "C", "Cc", "Cf", "Cs", "Co", "Cn");
    private static final Map<String, String> GENERAL_CATEGORY_LONG = Map.ofEntries(Map.entry("Letter", "L"),
            Map.entry("Uppercase_Letter", "Lu"), Map.entry("Lowercase_Letter", "Ll"),
            Map.entry("Titlecase_Letter", "Lt"), Map.entry("Modifier_Letter", "Lm"), Map.entry("Other_Letter", "Lo"),
            Map.entry("Mark", "M"), Map.entry("Nonspacing_Mark", "Mn"), Map.entry("Spacing_Mark", "Mc"),
            Map.entry("Enclosing_Mark", "Me"), Map.entry("Number", "N"), Map.entry("Decimal_Number", "Nd"),
            Map.entry("Letter_Number", "Nl"), Map.entry("Other_Number", "No"), Map.entry("Punctuation", "P"),
            Map.entry("Connector_Punctuation", "Pc"), Map.entry("Dash_Punctuation", "Pd"),
            Map.entry("Open_Punctuation", "Ps"), Map.entry("Close_Punctuation", "Pe"),
            Map.entry("Initial_Punctuation", "Pi"), Map.entry("Final_Punctuation", "Pf"),
            Map.entry("Other_Punctuation", "Po"), Map.entry("Symbol", "S"), Map.entry("Math_Symbol", "Sm"),
            Map.entry("Currency_Symbol", "Sc"), Map.entry("Modifier_Symbol", "Sk"), Map.entry("Other_Symbol", "So"),
            Map.entry("Separator", "Z"), Map.entry("Space_Separator", "Zs"), Map.entry("Line_Separator", "Zl"),
            Map.entry("Paragraph_Separator", "Zp"), Map.entry("Other", "C"), Map.entry("Control", "Cc"),
            Map.entry("Format", "Cf"), Map.entry("Surrogate", "Cs"), Map.entry("Private_Use", "Co"),
            Map.entry("Unassigned", "Cn"));
    private static final Map<String, String> BINARY_PROPERTIES = Map.ofEntries(Map.entry("Alphabetic", "IsAlphabetic"),
            Map.entry("White_Space", "IsWhite_Space"), Map.entry("Uppercase", "IsUppercase"),
            Map.entry("Lowercase", "IsLowercase"), Map.entry("Hex_Digit", "IsHexDigit"),
            Map.entry("Ideographic", "IsIdeographic"), Map.entry("Assigned", "IsAssigned"),
            Map.entry("Noncharacter_Code_Point", "IsNoncharacterCodePoint"), Map.entry("Join_Control", "IsJoinControl"),
            Map.entry("ASCII", "ASCII"), Map.entry("Any", "all"), Map.entry("ASCII_Hex_Digit", "XDigit"),
            Map.entry("Hex", "IsHexDigit"));
    private static final String SEQUENCES_RESOURCE = "/simplejs/emoji-sequences.txt";
    private static final String RGI_EMOJI = "RGI_Emoji";
    private static final Map<String, CodePointSet> CACHE = new ConcurrentHashMap<>();

    private UnicodeProperty() {
    }

    // Property names are matched exactly: ECMA-262 forbids the "loose matching" of UAX #44, so
    // surrounding whitespace or a different case is an early error rather than the same property.
    static String translate(String body) {
        final var equals = body.indexOf('=');
        if (equals >= 0) {
            final var key = body.substring(0, equals);
            final var value = body.substring(equals + 1);
            return switch (key) {
                case "Script", "sc", "Script_Extensions", "scx" -> "script=" + value;
                case "General_Category", "gc" -> categoryCode(value);
                default -> throw unsupported(body);
            };
        }
        if (GENERAL_CATEGORY_CODES.contains(body)) {
            return body;
        }
        final var longCode = GENERAL_CATEGORY_LONG.get(body);
        if (longCode != null) {
            return longCode;
        }
        final var binary = BINARY_PROPERTIES.get(body);
        if (binary != null) {
            return binary;
        }
        throw unsupported(body);
    }

    private static String categoryCode(String value) {
        if (GENERAL_CATEGORY_CODES.contains(value)) {
            return value;
        }
        final var longCode = GENERAL_CATEGORY_LONG.get(value);
        if (longCode != null) {
            return longCode;
        }
        throw unsupported(value);
    }

    static SyntaxErrorException unsupported(String name) {
        return new SyntaxErrorException("Invalid regular expression: unsupported Unicode property: " + name);
    }

    static CodePointSet rawSet(String javaProperty) {
        return CACHE.computeIfAbsent(javaProperty, UnicodeProperty::compute);
    }

    private static CodePointSet compute(String javaProperty) {
        if ("all".equals(javaProperty)) {
            return CodePointSet.ALL;
        }
        final Pattern pattern;
        try {
            pattern = Pattern.compile("\\p{" + javaProperty + "}");
        } catch (PatternSyntaxException notSupportedByJdk) {
            throw unsupported(javaProperty);
        }
        final var builder = new CodePointSet.Builder();
        var rangeStart = -1;
        for (var cp = 0; cp <= CodePointSet.MAX_CODE_POINT; cp++) {
            if (pattern.matcher(codePointText(cp)).matches()) {
                if (rangeStart < 0) {
                    rangeStart = cp;
                }
            } else if (rangeStart >= 0) {
                builder.addRange(rangeStart, cp - 1);
                rangeStart = -1;
            }
        }
        if (rangeStart >= 0) {
            builder.addRange(rangeStart, CodePointSet.MAX_CODE_POINT);
        }
        return builder.build();
    }

    private static String codePointText(int cp) {
        return cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE
                ? String.valueOf((char) cp)
                : new String(Character.toChars(cp));
    }

    /**
     * The ECMA-262 properties of strings, whose members are sequences of code points rather than
     * code points, so no {@code CodePointSet} can express them: they resolve to a fixed list of
     * literal string alternatives from a resource pinned to the Unicode version the test262 corpus
     * targets (regenerated by {@code test_utils/gen_emoji_sequences.py}).
     */
    static final class StringProperties {
        private static final Map<String, List<String>> DATA = load();

        private StringProperties() {
        }

        private static Map<String, List<String>> load() {
            final var loaded = new LinkedHashMap<String, List<String>>();
            final var rgi = new ArrayList<String>();
            final var resource = UnicodeProperty.class.getResourceAsStream(SEQUENCES_RESOURCE);
            if (resource == null) {
                throw new IllegalStateException("missing regex resource " + SEQUENCES_RESOURCE);
            }
            try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) != '#') {
                        final var tab = line.indexOf('\t');
                        final var sequence = decode(line.substring(tab + 1));
                        loaded.computeIfAbsent(line.substring(0, tab), _ -> new ArrayList<>()).add(sequence);
                        rgi.add(sequence);
                    }
                }
            } catch (IOException unreadable) {
                throw new IllegalStateException("unreadable regex resource " + SEQUENCES_RESOURCE, unreadable);
            }
            // RGI_Emoji is the union of the other six properties (UTS #51 ED-27), so it is derived
            // rather than duplicated in the resource.
            loaded.put(RGI_EMOJI, rgi);
            return loaded;
        }

        private static String decode(String codePoints) {
            final var text = new StringBuilder(8);
            for (final var point : codePoints.split(" ")) {
                text.appendCodePoint(Integer.parseInt(point, 16));
            }
            return text.toString();
        }

        static boolean has(String name) {
            return DATA.containsKey(name);
        }

        static List<String> get(String name) {
            return DATA.get(name);
        }
    }
}
