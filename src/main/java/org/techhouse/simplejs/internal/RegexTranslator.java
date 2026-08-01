package org.techhouse.simplejs.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private static final String VALID_FLAGS = "dgimsuvy";
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
            Map.entry("Noncharacter_Code_Point", "IsNoncharacterCodePoint"),
            Map.entry("Join_Control", "IsJoinControl"));

    private RegexTranslator() {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        validateFlags(normalizedFlags);
        final var unicode = normalizedFlags.indexOf('u') >= 0 || normalizedFlags.indexOf('v') >= 0;
        final var translated = unicode ? translateUnicodeProperties(source) : source;
        try {
            final var pattern = Pattern.compile(inlineFlags(normalizedFlags) + translated);
            return new JsRegExp(source, normalizedFlags, pattern);
        } catch (PatternSyntaxException syntax) {
            throw new SyntaxErrorException("Invalid regular expression: /" + source + "/: " + syntax.getMessage());
        }
    }

    private static String translateUnicodeProperties(String source) {
        final var out = new StringBuilder();
        var i = 0;
        while (i < source.length()) {
            final var c = source.charAt(i);
            if (c != '\\' || i + 1 >= source.length()) {
                out.append(c);
                i++;
                continue;
            }
            final var next = source.charAt(i + 1);
            if ((next == 'p' || next == 'P') && i + 2 < source.length() && source.charAt(i + 2) == '{') {
                final var close = source.indexOf('}', i + 3);
                if (close < 0) {
                    throw new SyntaxErrorException("Invalid regular expression: unterminated \\" + next + "{ property");
                }
                out.append('\\').append(next).append('{').append(translateProperty(source.substring(i + 3, close)))
                        .append('}');
                i = close + 1;
            } else {
                out.append(c).append(next);
                i += 2;
            }
        }
        return out.toString();
    }

    private static String translateProperty(String body) {
        final var equals = body.indexOf('=');
        if (equals >= 0) {
            final var key = body.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            final var value = body.substring(equals + 1).trim();
            return switch (key) {
                case "script", "sc", "script_extensions", "scx" -> "script=" + value;
                case "general_category", "gc" -> categoryCode(value);
                default -> throw unsupportedProperty(body);
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
        throw unsupportedProperty(body);
    }

    private static String categoryCode(String value) {
        if (GENERAL_CATEGORY_CODES.contains(value)) {
            return value;
        }
        final var longCode = GENERAL_CATEGORY_LONG.get(value);
        if (longCode != null) {
            return longCode;
        }
        throw unsupportedProperty(value);
    }

    private static SyntaxErrorException unsupportedProperty(String name) {
        return new SyntaxErrorException("Invalid regular expression: unsupported Unicode property: " + name);
    }

    private static void validateFlags(String flags) {
        for (var i = 0; i < flags.length(); i++) {
            final var flag = flags.charAt(i);
            if (VALID_FLAGS.indexOf(flag) < 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
            if (flags.indexOf(flag, i + 1) >= 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
        }
        if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
            throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
        }
    }

    private static String inlineFlags(String flags) {
        final var inline = new StringBuilder();
        if (flags.indexOf('i') >= 0) {
            inline.append('i');
        }
        if (flags.indexOf('m') >= 0) {
            inline.append('m');
        }
        if (flags.indexOf('s') >= 0) {
            inline.append('s');
        }
        return inline.isEmpty() ? "" : "(?" + inline + ")";
    }
}
