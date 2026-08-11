package org.techhouse.simplejs.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
            Map.entry("Noncharacter_Code_Point", "IsNoncharacterCodePoint"), Map.entry("Join_Control", "IsJoinControl"),
            Map.entry("ASCII", "ASCII"), Map.entry("Any", "all"));

    private RegexTranslator() {
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        validateFlags(normalizedFlags);
        final var unicodeSets = normalizedFlags.indexOf('v') >= 0;
        final var unicode = normalizedFlags.indexOf('u') >= 0 || unicodeSets;
        final String translated;
        if (unicodeSets) {
            translated = new SetTranslator(source).translate();
        } else if (unicode) {
            translated = translateUnicodeProperties(source);
        } else {
            translated = source;
        }
        final var renamed = renameDuplicateGroups(translated);
        try {
            final var pattern = Pattern.compile(inlineFlags(normalizedFlags) + renamed.pattern());
            return new JsRegExp(source, normalizedFlags, pattern, renamed.aliases());
        } catch (PatternSyntaxException syntax) {
            throw new SyntaxErrorException("Invalid regular expression: /" + source + "/: " + syntax.getMessage());
        }
    }

    private record GroupRename(String pattern, Map<String, List<String>> aliases) {
    }

    // ES2025 allows the same group name in different alternatives, which java.util.regex rejects, so
    // repeats are renamed and the original name keeps the list of java names it may have matched as.
    private static GroupRename renameDuplicateGroups(String pattern) {
        final var out = new StringBuilder();
        final var aliases = new LinkedHashMap<String, List<String>>();
        final var used = new HashSet<String>();
        var inClass = false;
        var i = 0;
        while (i < pattern.length()) {
            final var c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                final var consumed = appendBackreference(pattern, i, aliases, out);
                if (consumed > 0) {
                    i += consumed;
                } else {
                    out.append(c).append(pattern.charAt(i + 1));
                    i += 2;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (!inClass && isNamedGroupStart(pattern, i)) {
                final var close = pattern.indexOf('>', i + 3);
                if (close > 0) {
                    final var name = pattern.substring(i + 3, close);
                    out.append("(?<").append(uniqueName(name, aliases, used)).append('>');
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new GroupRename(out.toString(), aliases);
    }

    private static boolean isNamedGroupStart(String pattern, int index) {
        if (!pattern.startsWith("(?<", index) || index + 3 >= pattern.length()) {
            return false;
        }
        final var after = pattern.charAt(index + 3);
        return after != '=' && after != '!';
    }

    private static int appendBackreference(String pattern, int index, Map<String, List<String>> aliases,
            StringBuilder out) {
        if (pattern.charAt(index + 1) != 'k' || index + 2 >= pattern.length() || pattern.charAt(index + 2) != '<') {
            return 0;
        }
        final var close = pattern.indexOf('>', index + 3);
        if (close < 0) {
            return 0;
        }
        final var name = pattern.substring(index + 3, close);
        final var known = aliases.get(name);
        out.append("\\k<").append(known == null || known.isEmpty() ? name : known.getFirst()).append('>');
        return close + 1 - index;
    }

    private static String uniqueName(String name, Map<String, List<String>> aliases, Set<String> used) {
        final var declared = aliases.computeIfAbsent(name, _ -> new ArrayList<>());
        if (declared.isEmpty() && used.add(name)) {
            declared.add(name);
            return name;
        }
        var suffix = declared.size();
        while (!used.add(name + suffix)) {
            suffix++;
        }
        declared.add(name + suffix);
        return name + suffix;
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

    // Translates a `v`-mode (unicodeSets) source to a java.util.regex-compatible pattern. Outside a
    // character class it behaves like the `u`-mode property translator; inside a class it parses the
    // v-mode set notation (nested classes, `&&` intersection, `--` subtraction, `\q{}` string
    // literals) and rewrites subtraction to Java's intersection-with-negation form.
    private static final class SetTranslator {
        private final String source;
        private int pos;

        SetTranslator(String source) {
            this.source = source;
        }

        private String translate() {
            final var out = new StringBuilder();
            while (pos < source.length()) {
                final var c = source.charAt(pos);
                if (c == '\\') {
                    out.append(readEscapeOutsideClass());
                } else if (c == '[') {
                    out.append(readClass());
                } else {
                    out.append(c);
                    pos++;
                }
            }
            return out.toString();
        }

        private String readEscapeOutsideClass() {
            if (pos + 1 >= source.length()) {
                pos++;
                return "\\";
            }
            final var next = source.charAt(pos + 1);
            if ((next == 'p' || next == 'P') && pos + 2 < source.length() && source.charAt(pos + 2) == '{') {
                return readProperty();
            }
            final var escape = source.substring(pos, pos + 2);
            pos += 2;
            return escape;
        }

        private String readProperty() {
            final var kind = source.charAt(pos + 1);
            final var close = source.indexOf('}', pos + 3);
            if (close < 0) {
                throw new SyntaxErrorException("Invalid regular expression: unterminated \\" + kind + "{ property");
            }
            final var body = source.substring(pos + 3, close);
            pos = close + 1;
            return "\\" + kind + "{" + translateProperty(body) + "}";
        }

        private String readClass() {
            pos++;
            var negated = false;
            if (pos < source.length() && source.charAt(pos) == '^') {
                negated = true;
                pos++;
            }
            final List<String> operands = new ArrayList<>();
            final var current = new StringBuilder();
            String operator = null;
            while (pos < source.length() && source.charAt(pos) != ']') {
                final var c = source.charAt(pos);
                if (c == '[') {
                    current.append(stripBrackets(readClass()));
                } else if (c == '&' && source.startsWith("&&", pos)) {
                    operator = pushOperand(operands, current, operator, "&&");
                    pos += 2;
                } else if (c == '-' && source.startsWith("--", pos)) {
                    operator = pushOperand(operands, current, operator, "--");
                    pos += 2;
                } else if (c == '\\') {
                    current.append(readClassEscape());
                } else {
                    current.append(c);
                    pos++;
                }
            }
            if (pos >= source.length()) {
                throw new SyntaxErrorException("Invalid regular expression: unterminated character class");
            }
            pos++;
            operands.add(current.toString());
            return buildClass(negated, operator, operands);
        }

        private String readClassEscape() {
            if (pos + 1 >= source.length()) {
                pos++;
                return "\\";
            }
            final var next = source.charAt(pos + 1);
            if ((next == 'p' || next == 'P') && pos + 2 < source.length() && source.charAt(pos + 2) == '{') {
                return readProperty();
            }
            if (next == 'q' && pos + 2 < source.length() && source.charAt(pos + 2) == '{') {
                return readStringLiteral();
            }
            final var escape = source.substring(pos, pos + 2);
            pos += 2;
            return escape;
        }

        private String readStringLiteral() {
            final var close = source.indexOf('}', pos + 3);
            if (close < 0) {
                throw new SyntaxErrorException("Invalid regular expression: unterminated \\q{ string literal");
            }
            final var body = source.substring(pos + 3, close);
            pos = close + 1;
            final var out = new StringBuilder();
            for (final var part : body.split("\\|", -1)) {
                if (part.isEmpty() || part.codePointCount(0, part.length()) != 1) {
                    throw new SyntaxErrorException(
                            "Invalid regular expression: multi-character \\q{} string literals are not supported");
                }
                out.append(escapeClassChar(part));
            }
            return out.toString();
        }

        private String pushOperand(List<String> operands, StringBuilder current, String existing, String operator) {
            if (existing != null && !existing.equals(operator)) {
                throw new SyntaxErrorException(
                        "Invalid regular expression: cannot mix set operators in a character class");
            }
            operands.add(current.toString());
            current.setLength(0);
            return operator;
        }

        private String buildClass(boolean negated, String operator, List<String> operands) {
            final var prefix = negated ? "^" : "";
            if (operator == null) {
                return "[" + prefix + operands.getFirst() + "]";
            }
            if ("&&".equals(operator)) {
                final var out = new StringBuilder("[").append(prefix);
                for (var i = 0; i < operands.size(); i++) {
                    out.append('[').append(operands.get(i)).append(']');
                    if (i < operands.size() - 1) {
                        out.append("&&");
                    }
                }
                return out.append(']').toString();
            }
            final var rest = new StringBuilder();
            for (var i = 1; i < operands.size(); i++) {
                rest.append(operands.get(i));
            }
            return "[" + prefix + "[" + operands.getFirst() + "]&&[^" + rest + "]]";
        }

        private static String stripBrackets(String cls) {
            if (cls.length() >= 2 && cls.charAt(0) == '[' && cls.charAt(cls.length() - 1) == ']') {
                return cls.substring(1, cls.length() - 1);
            }
            return cls;
        }

        private static String escapeClassChar(String member) {
            final var out = new StringBuilder();
            for (var i = 0; i < member.length(); i++) {
                final var c = member.charAt(i);
                if ("\\]^-[&".indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
            }
            return out.toString();
        }
    }
}
