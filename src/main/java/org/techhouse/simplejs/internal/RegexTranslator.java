package org.techhouse.simplejs.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.values.JsRegExp;

public final class RegexTranslator {
    private static final String VALID_FLAGS = "dgimsuvy";
    private static final String SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|";
    private static final String SET_SYNTAX_CHARACTERS = "()[]{}/-\\|";
    private static final String RESERVED_DOUBLE_PUNCTUATORS = "&!#$%*+,.:;<=>?@^`~";
    private static final String MODIFIER_FLAGS = "ims";
    // ECMA-262 WhiteSpace + LineTerminator. Spelled out rather than delegating to java.util.regex's
    // \s, which is ASCII-only, or to UNICODE_CHARACTER_CLASS, which would also widen \d/\w/\b.
    private static final String WHITESPACE_BODY = " \\t\\n\\x0B\\f\\r\\u00a0\\u1680\\u2000-\\u200a"
            + "\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
    private static final String LINE_TERMINATOR_BODY = "\\n\\r\\u2028\\u2029";
    private static final String EMPTY_CLASS = "[^\\s\\S]";
    private static final String ANY_CLASS = "[\\s\\S]";
    // Without u or v a pattern matches code units, so `.` must not consume a whole supplementary
    // code point the way java.util.regex does; restricting it to the BMP is how that is spelled.
    private static final String CODE_UNIT_BODY = "\\x{0}-\\x{ffff}";
    // GetWordCharacters: under ignoreCase in unicode mode the two code points that case-fold into
    // the ASCII word set join it.
    private static final String WORD_BODY = "a-zA-Z0-9_";
    private static final String FOLDED_WORD_BODY = WORD_BODY + "\\u017f\\u212a";
    private static final int MAX_REPETITION = 0x7FFF_FFFE;

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

    private RegexTranslator() {
    }

    /**
     * The ECMA-262 properties of strings, whose members are sequences of code points rather than
     * code points, so {@code java.util.regex} has no data for them: they are translated into an
     * alternation of literal sequences from a resource pinned to the Unicode version the test262
     * corpus targets (regenerated by {@code test_utils/gen_emoji_sequences.py}).
     */
    private static final class StringProperties {
        private static final Map<String, List<String>> DATA = load();

        private StringProperties() {
        }

        private static Map<String, List<String>> load() {
            final var loaded = new LinkedHashMap<String, List<String>>();
            final var rgi = new ArrayList<String>();
            final var resource = RegexTranslator.class.getResourceAsStream(SEQUENCES_RESOURCE);
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

        private static boolean has(String name) {
            return DATA.containsKey(name);
        }

        private static List<String> get(String name) {
            return DATA.get(name);
        }
    }

    public static JsRegExp compile(String source, String flags) {
        final var normalizedFlags = flags == null ? "" : flags;
        validateFlags(normalizedFlags);
        final var parser = new PatternParser(source, normalizedFlags);
        final var translated = parser.translate();
        try {
            final var pattern = Pattern.compile(translated);
            return new JsRegExp(source, normalizedFlags, pattern, parser.aliases());
        } catch (PatternSyntaxException syntax) {
            throw new SyntaxErrorException("Invalid regular expression: /" + source + "/: " + syntax.getMessage());
        }
    }

    private static void validateFlags(String flags) {
        for (var i = 0; i < flags.length(); i++) {
            final var flag = flags.charAt(i);
            if (VALID_FLAGS.indexOf(flag) < 0 || flags.indexOf(flag, i + 1) >= 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
        }
        if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
            throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
        }
    }

    // Property names are matched exactly: ECMA-262 forbids the "loose matching" of UAX #44, so
    // surrounding whitespace or a different case is an early error rather than the same property.
    private static String translateProperty(String body) {
        final var equals = body.indexOf('=');
        if (equals >= 0) {
            final var key = body.substring(0, equals);
            final var value = body.substring(equals + 1);
            return switch (key) {
                case "Script", "sc", "Script_Extensions", "scx" -> "script=" + value;
                case "General_Category", "gc" -> categoryCode(value);
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

    private static SyntaxErrorException invalid(String detail) {
        return new SyntaxErrorException("Invalid regular expression: " + detail);
    }

    // A v-mode class operand: the code points it accepts (as a java.util.regex class body) plus the
    // multi-code-point alternatives contributed by \q{}, which no character class can express.
    private static final class ClassSet {
        private final StringBuilder chars = new StringBuilder(32);
        private final List<String> strings = new ArrayList<>();

        private boolean isEmpty() {
            return chars.isEmpty() && strings.isEmpty();
        }
    }

    /**
     * Recursive-descent translator from an ECMA-262 {@code Pattern} to a {@code java.util.regex} one.
     * It is also the validator: the early errors the two grammars disagree on (Annex B leniency,
     * {@code u}/{@code v} strictness, modifier groups, {@code v}-mode set notation) are raised here
     * rather than left to {@code Pattern.compile}, which has its own, different, notion of invalid.
     */
    private static final class PatternParser {
        private final String source;
        private final boolean unicode;
        private final boolean unicodeSets;
        private final StringBuilder out = new StringBuilder(256);
        private final Map<String, List<String>> aliases = new LinkedHashMap<>();
        private final List<String> declarations = new ArrayList<>();
        private final Set<String> opened = new LinkedHashSet<>();
        private int capturingGroups;
        private int namedSeen;
        private int openedGroups;
        private int pos;
        private boolean multiline;
        private boolean dotAll;
        private boolean ignoreCase;

        PatternParser(String source, String flags) {
            this.source = source;
            this.unicodeSets = flags.indexOf('v') >= 0;
            this.unicode = flags.indexOf('u') >= 0 || unicodeSets;
            this.ignoreCase = flags.indexOf('i') >= 0;
            this.multiline = flags.indexOf('m') >= 0;
            this.dotAll = flags.indexOf('s') >= 0;
        }

        Map<String, List<String>> aliases() {
            return aliases;
        }

        String translate() {
            prescan();
            if (ignoreCase) {
                out.append(unicode ? "(?iu)" : "(?i)");
            }
            parseDisjunction();
            if (pos < source.length()) {
                throw invalid("unmatched ')'");
            }
            return out.toString();
        }

        // Capturing groups are numbered before the body is walked so a backreference may point
        // forward, and every named group is pre-assigned a java-legal synthetic name (java only
        // accepts [A-Za-z][A-Za-z0-9]*, ECMA-262 accepts any IdentifierName).
        private void prescan() {
            var depth = 0;
            var i = 0;
            while (i < source.length()) {
                final var c = source.charAt(i);
                if (c == '\\') {
                    i += 2;
                } else if (c == '[') {
                    depth += unicodeSets || depth == 0 ? 1 : 0;
                    i++;
                } else if (c == ']') {
                    depth = Math.max(0, depth - 1);
                    i++;
                } else if (depth == 0 && c == '(') {
                    i = prescanGroup(i);
                } else {
                    i++;
                }
            }
            var synthetic = 0;
            for (final var name : declarations) {
                aliases.computeIfAbsent(name, _ -> new ArrayList<>()).add("g" + synthetic);
                synthetic++;
            }
        }

        private int prescanGroup(int index) {
            if (index + 1 >= source.length() || source.charAt(index + 1) != '?') {
                capturingGroups++;
                return index + 1;
            }
            if (source.startsWith("(?<", index) && index + 3 < source.length()
                    && "=!".indexOf(source.charAt(index + 3)) < 0) {
                final var close = source.indexOf('>', index + 3);
                if (close < 0) {
                    throw invalid("unterminated group name");
                }
                capturingGroups++;
                declarations.add(decodeGroupName(source.substring(index + 3, close)));
                return close + 1;
            }
            return index + 2;
        }

        private char peek() {
            return source.charAt(pos);
        }

        private boolean has(int offset) {
            return pos + offset < source.length();
        }

        private boolean startsWith(String prefix) {
            return source.startsWith(prefix, pos);
        }

        private Set<String> parseDisjunction() {
            final var names = new LinkedHashSet<>(parseAlternative());
            while (pos < source.length() && peek() == '|') {
                out.append('|');
                pos++;
                names.addAll(parseAlternative());
            }
            return names;
        }

        private Set<String> parseAlternative() {
            final var names = new LinkedHashSet<String>();
            while (pos < source.length() && peek() != '|' && peek() != ')') {
                for (final var name : parseTerm()) {
                    if (!names.add(name)) {
                        throw invalid("duplicate capture group name '" + name + "'");
                    }
                }
            }
            return names;
        }

        private Set<String> parseTerm() {
            if (isAssertion()) {
                emitAssertion();
                rejectQuantifier();
                return Set.of();
            }
            if (startsWith("(?=") || startsWith("(?!")) {
                final var names = parseLookaround(source.substring(pos, pos + 3));
                if (unicode) {
                    rejectQuantifier();
                } else {
                    parseQuantifier();
                }
                return names;
            }
            if (startsWith("(?<=") || startsWith("(?<!")) {
                final var names = parseLookaround(source.substring(pos, pos + 4));
                rejectQuantifier();
                return names;
            }
            final var names = parseAtom();
            parseQuantifier();
            return names;
        }

        private boolean isAssertion() {
            final var c = peek();
            return c == '^' || c == '$'
                    || (c == '\\' && has(1) && (source.charAt(pos + 1) == 'b' || source.charAt(pos + 1) == 'B'));
        }

        private void emitAssertion() {
            final var c = peek();
            if (c == '^') {
                out.append(multiline ? "(?m:^)" : "\\A");
                pos++;
            } else if (c == '$') {
                out.append(multiline ? "(?m:$)" : "\\z");
                pos++;
            } else {
                out.append(wordBoundary(source.charAt(pos + 1) == 'B'));
                pos += 2;
            }
        }

        // java's \b is fixed to its own word definition, so a widened word set has to be spelled out
        // as the pair of lookarounds the assertion means.
        private String wordBoundary(boolean negated) {
            if (areNotFoldedWords()) {
                return negated ? "\\B" : "\\b";
            }
            final var word = "[" + FOLDED_WORD_BODY + "]";
            return negated
                    ? "(?:(?<=" + word + ")(?=" + word + ")|(?<!" + word + ")(?!" + word + "))"
                    : "(?:(?<!" + word + ")(?=" + word + ")|(?<=" + word + ")(?!" + word + "))";
        }

        private Set<String> parseLookaround(String opener) {
            out.append(opener);
            pos += opener.length();
            final var names = parseDisjunction();
            expect(')');
            out.append(')');
            return names;
        }

        private void expect(char expected) {
            if (pos >= source.length() || peek() != expected) {
                throw invalid("expected '" + expected + "'");
            }
            pos++;
        }

        private Set<String> parseAtom() {
            final var c = peek();
            return switch (c) {
                case '.' -> {
                    out.append(dot());
                    pos++;
                    yield Set.of();
                }
                case '\\' -> {
                    emitAtomEscape();
                    yield Set.of();
                }
                case '(' -> parseGroup();
                case '[' -> {
                    out.append(unicodeSets ? renderSet(parseSetClass()) : parseClass());
                    yield Set.of();
                }
                case '*', '+', '?' -> throw invalid("nothing to repeat");
                case '{' -> {
                    if (unicode || readQuantifierBraces(pos) > pos) {
                        throw invalid("nothing to repeat");
                    }
                    out.append("\\{");
                    pos++;
                    yield Set.of();
                }
                case ']', '}' -> {
                    if (unicode) {
                        throw invalid("lone quantifier brackets");
                    }
                    out.append('\\').append(c);
                    pos++;
                    yield Set.of();
                }
                default -> {
                    final var codePoint = source.codePointAt(pos);
                    emitLiteral(codePoint);
                    pos += Character.charCount(codePoint);
                    yield Set.of();
                }
            };
        }

        // Without u or v, `.` is one *code unit*, which java.util.regex cannot express: it always
        // consumes a whole code point and can never match, or even look ahead at, half of a
        // well-formed surrogate pair. Restricting the dotAll spelling to the BMP at least stops a
        // supplementary code point being matched as a single character. The non-dotAll spelling
        // keeps java's behaviour, because restricting it would stop `/c./` matching at all where
        // the code-unit semantics matches the leading surrogate.
        private String dot() {
            if (dotAll) {
                return unicode ? "(?s:.)" : "[" + CODE_UNIT_BODY + "]";
            }
            return "[^" + LINE_TERMINATOR_BODY + "]";
        }

        // GetWordCharacters only widens under unicode mode: without u or v Canonicalize leaves a
        // non-ASCII code point alone even when its uppercase form is an ASCII word character.
        private boolean areNotFoldedWords() {
            return !ignoreCase || !unicode;
        }

        private String wordClass(boolean negated) {
            if (areNotFoldedWords()) {
                return negated ? "\\W" : "\\w";
            }
            return "[" + (negated ? "^" : "") + FOLDED_WORD_BODY + "]";
        }

        private void emitLiteral(int codePoint) {
            if (codePoint < 0x80 && (Character.isLetterOrDigit(codePoint) || codePoint == '_')) {
                out.append((char) codePoint);
            } else {
                out.append("\\x{").append(Integer.toHexString(codePoint)).append('}');
            }
        }

        private Set<String> parseGroup() {
            if (startsWith("(?:")) {
                return parseWrapped("(?:");
            }
            if (startsWith("(?<")) {
                return parseNamedGroup();
            }
            if (startsWith("(?")) {
                return parseModifierGroup();
            }
            openedGroups++;
            return parseWrapped("(");
        }

        private Set<String> parseWrapped(String opener) {
            out.append(opener);
            pos += opener.length();
            final var names = parseDisjunction();
            expect(')');
            out.append(')');
            return names;
        }

        private Set<String> parseNamedGroup() {
            final var close = source.indexOf('>', pos + 3);
            if (close < 0) {
                throw invalid("unterminated group name");
            }
            final var name = decodeGroupName(source.substring(pos + 3, close));
            validateGroupName(name);
            final var java = aliases.get(name).get(occurrenceOf(name));
            namedSeen++;
            openedGroups++;
            out.append("(?<").append(java).append('>');
            pos = close + 1;
            final var names = parseDisjunction();
            expect(')');
            out.append(')');
            // Only after the body: a reference to the group from inside itself cannot have
            // participated, so it is a forward reference matching the empty string.
            opened.add(java);
            final var declared = new LinkedHashSet<String>();
            declared.add(name);
            declared.addAll(names);
            return declared;
        }

        private int occurrenceOf(String name) {
            var seen = 0;
            for (var i = 0; i < namedSeen; i++) {
                if (declarations.get(i).equals(name)) {
                    seen++;
                }
            }
            return seen;
        }

        // A GroupName is an IdentifierName, so it may spell its code points as a unicode escape; both
        // the declaration and every backreference to it must agree on the decoded form.
        private static String decodeGroupName(String raw) {
            if (raw.indexOf('\\') < 0) {
                return raw;
            }
            final var out = new StringBuilder();
            var i = 0;
            while (i < raw.length()) {
                if (raw.charAt(i) != '\\' || i + 1 >= raw.length() || raw.charAt(i + 1) != 'u') {
                    out.append(raw.charAt(i));
                    i++;
                    continue;
                }
                if (i + 2 < raw.length() && raw.charAt(i + 2) == '{') {
                    final var close = raw.indexOf('}', i + 3);
                    if (close < 0) {
                        throw invalid("invalid capture group name '" + raw + "'");
                    }
                    out.appendCodePoint(parseCodePoint(raw.substring(i + 3, close), raw));
                    i = close + 1;
                    continue;
                }
                if (i + 6 > raw.length()) {
                    throw invalid("invalid capture group name '" + raw + "'");
                }
                out.append((char) parseCodePoint(raw.substring(i + 2, i + 6), raw));
                i += 6;
            }
            return out.toString();
        }

        private static int parseCodePoint(String hex, String raw) {
            try {
                final var value = Integer.parseInt(hex, 16);
                if (value > Character.MAX_CODE_POINT) {
                    throw invalid("invalid capture group name '" + raw + "'");
                }
                return value;
            } catch (NumberFormatException notHex) {
                throw invalid("invalid capture group name '" + raw + "'");
            }
        }

        private static void validateGroupName(String name) {
            if (name.isEmpty()) {
                throw invalid("empty capture group name");
            }
            var i = 0;
            while (i < name.length()) {
                final var cp = name.codePointAt(i);
                final var ok = i == 0
                        ? Character.isUnicodeIdentifierStart(cp) || cp == '$' || cp == '_'
                        : Character.isUnicodeIdentifierPart(cp) || cp == '$' || cp == 0x200C || cp == 0x200D;
                if (!ok) {
                    throw invalid("invalid capture group name '" + name + "'");
                }
                i += Character.charCount(cp);
            }
        }

        // Modifier groups: (?ims-ims: ... ). Each set must be free of repeats, the two must be
        // disjoint, at least one must be non-empty, and the colon is mandatory.
        private Set<String> parseModifierGroup() {
            var cursor = pos + 2;
            final var add = readModifiers(cursor);
            cursor += add.length();
            var remove = "";
            if (cursor < source.length() && source.charAt(cursor) == '-') {
                cursor++;
                remove = readModifiers(cursor);
                cursor += remove.length();
                if (remove.isEmpty() && add.isEmpty()) {
                    throw invalid("invalid regular expression modifiers");
                }
            } else if (add.isEmpty()) {
                throw invalid("invalid group");
            }
            if (cursor >= source.length() || source.charAt(cursor) != ':') {
                throw invalid("invalid regular expression modifiers");
            }
            checkModifiers(add, remove);
            return emitModifierGroup(add, remove, cursor + 1);
        }

        private String readModifiers(int from) {
            var end = from;
            while (end < source.length() && MODIFIER_FLAGS.indexOf(source.charAt(end)) >= 0) {
                end++;
            }
            return source.substring(from, end);
        }

        private static void checkModifiers(String add, String remove) {
            for (var i = 0; i < add.length(); i++) {
                if (add.indexOf(add.charAt(i), i + 1) >= 0 || remove.indexOf(add.charAt(i)) >= 0) {
                    throw invalid("invalid regular expression modifiers");
                }
            }
            for (var i = 0; i < remove.length(); i++) {
                if (remove.indexOf(remove.charAt(i), i + 1) >= 0) {
                    throw invalid("invalid regular expression modifiers");
                }
            }
        }

        private Set<String> emitModifierGroup(String add, String remove, int bodyStart) {
            final var previousMultiline = multiline;
            final var previousDotAll = dotAll;
            final var previousIgnoreCase = ignoreCase;
            multiline = add.indexOf('m') >= 0 || (multiline && remove.indexOf('m') < 0);
            dotAll = add.indexOf('s') >= 0 || (dotAll && remove.indexOf('s') < 0);
            ignoreCase = add.indexOf('i') >= 0 || (ignoreCase && remove.indexOf('i') < 0);
            final var javaAdd = add.replace("m", "").replace("s", "");
            final var javaRemove = remove.replace("m", "").replace("s", "");
            out.append("(?").append(javaAdd.indexOf('i') >= 0 && unicode ? javaAdd + "u" : javaAdd);
            if (!javaRemove.isEmpty()) {
                out.append('-').append(javaRemove);
            }
            out.append(':');
            pos = bodyStart;
            final var names = parseDisjunction();
            expect(')');
            out.append(')');
            multiline = previousMultiline;
            dotAll = previousDotAll;
            ignoreCase = previousIgnoreCase;
            return names;
        }

        private void rejectQuantifier() {
            if (pos < source.length()
                    && (peek() == '*' || peek() == '+' || peek() == '?' || readQuantifierBraces(pos) > pos)) {
                throw invalid("nothing to repeat");
            }
        }

        private void parseQuantifier() {
            if (pos >= source.length()) {
                return;
            }
            final var c = peek();
            if (c == '*' || c == '+' || c == '?') {
                out.append(c);
                pos++;
            } else if (c == '{') {
                final var end = readQuantifierBraces(pos);
                if (end == pos) {
                    return;
                }
                emitBraces(source.substring(pos + 1, end - 1));
                pos = end;
            } else {
                return;
            }
            if (pos < source.length() && peek() == '?') {
                out.append('?');
                pos++;
            }
        }

        private void emitBraces(String body) {
            final var comma = body.indexOf(',');
            final var min = clampRepetition(comma < 0 ? body : body.substring(0, comma));
            if (comma < 0) {
                out.append('{').append(min).append('}');
                return;
            }
            if (comma == body.length() - 1) {
                out.append('{').append(min).append(",}");
                return;
            }
            final var max = clampRepetition(body.substring(comma + 1));
            if (min > max) {
                throw invalid("numbers out of order in {} quantifier");
            }
            out.append('{').append(min).append(',').append(max).append('}');
        }

        private static long clampRepetition(String digits) {
            try {
                return Math.min(Long.parseLong(digits), MAX_REPETITION);
            } catch (NumberFormatException overflow) {
                return MAX_REPETITION;
            }
        }

        // Returns the index just past a well-formed {n} / {n,} / {n,m}, or `from` when the brace does
        // not open a quantifier at all (Annex B then treats it as a literal).
        private int readQuantifierBraces(int from) {
            if (from >= source.length() || source.charAt(from) != '{') {
                return from;
            }
            var i = from + 1;
            final var digitsStart = i;
            while (i < source.length() && Character.isDigit(source.charAt(i))) {
                i++;
            }
            if (i == digitsStart) {
                return from;
            }
            if (i < source.length() && source.charAt(i) == ',') {
                i++;
                final var secondStart = i;
                while (i < source.length() && Character.isDigit(source.charAt(i))) {
                    i++;
                }
                if (i == secondStart && (i >= source.length() || source.charAt(i) != '}')) {
                    return from;
                }
            }
            return i < source.length() && source.charAt(i) == '}' ? i + 1 : from;
        }

        private void emitAtomEscape() {
            if (!has(1)) {
                throw invalid("\\ at end of pattern");
            }
            final var c = source.charAt(pos + 1);
            switch (c) {
                case 'd', 'D' -> {
                    out.append('\\').append(c);
                    pos += 2;
                }
                case 'w', 'W' -> {
                    out.append(wordClass(c == 'W'));
                    pos += 2;
                }
                case 's' -> {
                    out.append('[').append(WHITESPACE_BODY).append(']');
                    pos += 2;
                }
                case 'S' -> {
                    out.append("[^").append(WHITESPACE_BODY).append(']');
                    pos += 2;
                }
                case 'k' -> emitNamedBackreference();
                case 'p', 'P' -> emitProperty();
                default -> out.append(readCharacterEscape(false));
            }
        }

        private void emitNamedBackreference() {
            if (!has(2) || source.charAt(pos + 2) != '<' || (aliases.isEmpty() && !unicode)) {
                if (unicode || !aliases.isEmpty()) {
                    throw invalid("invalid named reference");
                }
                out.append("\\x{6b}");
                pos += 2;
                return;
            }
            final var close = source.indexOf('>', pos + 3);
            if (close < 0) {
                throw invalid("invalid named reference");
            }
            final var name = decodeGroupName(source.substring(pos + 3, close));
            final var targets = aliases.get(name);
            if (targets == null) {
                throw invalid("invalid named reference to '" + name + "'");
            }
            pos = close + 1;
            // java rejects a forward reference outright, while ECMA-262 makes it match the empty
            // string (the group cannot have participated yet), which an empty group expresses.
            final var declared = targets.stream().filter(opened::contains).toList();
            if (declared.isEmpty()) {
                out.append("(?:)");
                return;
            }
            if (declared.size() == 1) {
                out.append("\\k<").append(declared.getFirst()).append('>');
                return;
            }
            // Only one alias of a duplicated name can have participated; a java backreference to a
            // group that did not participate simply fails, so the alternation picks the right one.
            out.append("(?:");
            for (var i = 0; i < declared.size(); i++) {
                out.append(i == 0 ? "" : "|").append("\\k<").append(declared.get(i)).append('>');
            }
            out.append(')');
        }

        private void emitProperty() {
            final var kind = source.charAt(pos + 1);
            if (!unicode) {
                out.append("\\x{").append(Integer.toHexString(kind)).append('}');
                pos += 2;
                return;
            }
            final var close = propertyBodyEnd(kind);
            final var body = source.substring(pos + 3, close);
            pos = close + 1;
            if (StringProperties.has(body)) {
                out.append(renderSet(stringPropertySet(body, kind)));
                return;
            }
            out.append('\\').append(kind).append('{').append(translateProperty(body)).append('}');
        }

        private int propertyBodyEnd(char kind) {
            if (!has(2) || source.charAt(pos + 2) != '{') {
                throw invalid("incomplete \\" + kind + "{} property");
            }
            final var close = source.indexOf('}', pos + 3);
            if (close < 0) {
                throw invalid("unterminated \\" + kind + "{ property");
            }
            return close;
        }

        // A property of strings may only appear under the v flag, and never negated: it can match
        // more than one code point, which no complement can express.
        private ClassSet stringPropertySet(String name, char kind) {
            if (!unicodeSets || kind == 'P') {
                throw invalid("property of strings '" + name + "' requires the v flag and may not be negated");
            }
            final var set = new ClassSet();
            for (final var sequence : StringProperties.get(name)) {
                flushAlternative(set, new StringBuilder(sequence));
            }
            return set;
        }

        // Shared by the atom and the character-class paths: consumes one escape sequence starting at
        // the backslash and returns its java.util.regex spelling.
        private String readCharacterEscape(boolean inClass) {
            final var c = source.charAt(pos + 1);
            return switch (c) {
                case 'f', 'n', 'r', 't' -> {
                    pos += 2;
                    yield "\\" + c;
                }
                case 'v' -> {
                    pos += 2;
                    yield "\\x0B";
                }
                case 'b' -> {
                    if (!inClass) {
                        throw invalid("unexpected \\b");
                    }
                    pos += 2;
                    yield "\\x08";
                }
                case 'c' -> readControlEscape(inClass);
                case 'x' -> readHexEscape();
                case 'u' -> readUnicodeEscape();
                case '0' -> readNullEscape();
                case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readDecimalEscape(inClass);
                case '-' -> {
                    // \- is a ClassEscape only: outside a class it is an ordinary IdentityEscape,
                    // which u mode restricts to the syntax characters.
                    if (!inClass && unicode) {
                        throw invalid("invalid escape '\\-'");
                    }
                    pos += 2;
                    yield "\\-";
                }
                default -> readIdentityEscape(c);
            };
        }

        private String readIdentityEscape(char c) {
            if (unicode && SYNTAX_CHARACTERS.indexOf(c) < 0 && c != '/') {
                throw invalid("invalid escape '\\" + c + "'");
            }
            pos += 2;
            return "\\x{" + Integer.toHexString(c) + "}";
        }

        private String readControlEscape(boolean inClass) {
            if (has(2) && Character.isLetter(source.charAt(pos + 2)) && source.charAt(pos + 2) < 0x80) {
                final var letter = source.charAt(pos + 2);
                pos += 3;
                return "\\c" + letter;
            }
            if (unicode) {
                throw invalid("invalid control escape");
            }
            if (inClass && has(2) && (Character.isDigit(source.charAt(pos + 2)) || source.charAt(pos + 2) == '_')) {
                final var letter = source.charAt(pos + 2);
                pos += 3;
                return "\\x{" + Integer.toHexString(letter % 32) + "}";
            }
            pos += 2;
            return "\\x{5c}\\x{63}";
        }

        private String readHexEscape() {
            if (hexRun(pos + 2, 2) == 2) {
                final var value = source.substring(pos + 2, pos + 4);
                pos += 4;
                return "\\x" + value;
            }
            if (unicode) {
                throw invalid("invalid hexadecimal escape");
            }
            pos += 2;
            return "\\x{78}";
        }

        private String readUnicodeEscape() {
            if (unicode && has(2) && source.charAt(pos + 2) == '{') {
                final var close = source.indexOf('}', pos + 3);
                if (close < 0 || close == pos + 3) {
                    throw invalid("invalid unicode escape");
                }
                final var body = source.substring(pos + 3, close);
                final long value;
                try {
                    value = Long.parseLong(body, 16);
                } catch (NumberFormatException notHex) {
                    throw invalid("invalid unicode escape");
                }
                if (value > Character.MAX_CODE_POINT) {
                    throw invalid("unicode escape out of range");
                }
                pos = close + 1;
                return "\\x{" + Long.toHexString(value) + "}";
            }
            if (hexRun(pos + 2, 4) == 4) {
                final var value = source.substring(pos + 2, pos + 6);
                pos += 6;
                return "\\u" + value;
            }
            if (unicode) {
                throw invalid("invalid unicode escape");
            }
            pos += 2;
            return "\\x{75}";
        }

        private int hexRun(int from, int wanted) {
            var count = 0;
            while (count < wanted && from + count < source.length()
                    && Character.digit(source.charAt(from + count), 16) >= 0) {
                count++;
            }
            return count;
        }

        private String readNullEscape() {
            if (has(2) && Character.isDigit(source.charAt(pos + 2))) {
                if (unicode) {
                    throw invalid("invalid legacy octal escape");
                }
                return readOctalEscape();
            }
            pos += 2;
            return "\\x00";
        }

        private String readDecimalEscape(boolean inClass) {
            var end = pos + 1;
            while (end < source.length() && Character.isDigit(source.charAt(end))) {
                end++;
            }
            final var digits = source.substring(pos + 1, end);
            final var value = digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
            if (!inClass && value <= capturingGroups) {
                pos = end;
                return value <= openedGroups ? "\\" + digits : "(?:)";
            }
            if (unicode) {
                throw invalid("invalid decimal escape");
            }
            if (source.charAt(pos + 1) >= '8') {
                pos += 2;
                return "\\x{" + Integer.toHexString(source.charAt(pos - 1)) + "}";
            }
            return readOctalEscape();
        }

        private String readOctalEscape() {
            var end = pos + 1;
            var value = 0;
            while (end < source.length() && end < pos + 4 && source.charAt(end) >= '0' && source.charAt(end) <= '7'
                    && value * 8 + (source.charAt(end) - '0') <= 0xFF) {
                value = value * 8 + (source.charAt(end) - '0');
                end++;
            }
            pos = end;
            return "\\x{" + Integer.toHexString(value) + "}";
        }

        private String parseClass() {
            pos++;
            final var negated = pos < source.length() && peek() == '^';
            if (negated) {
                pos++;
            }
            final var body = new StringBuilder();
            var empty = true;
            while (pos < source.length() && peek() != ']') {
                empty = false;
                appendClassRange(body);
            }
            expect(']');
            if (empty) {
                return negated ? ANY_CLASS : EMPTY_CLASS;
            }
            return "[" + (negated ? "^" : "") + body + "]";
        }

        private void appendClassRange(StringBuilder body) {
            final var low = readClassAtom();
            if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']') {
                pos++;
                final var high = readClassAtom();
                if (low.set() || high.set()) {
                    if (unicode) {
                        throw invalid("invalid character class range");
                    }
                    body.append(low.text()).append("\\-").append(high.text());
                    return;
                }
                body.append(low.text()).append('-').append(high.text());
                return;
            }
            body.append(low.text());
        }

        private record ClassAtom(String text, boolean set) {
        }

        private ClassAtom readClassAtom() {
            final var c = peek();
            if (c != '\\') {
                final var cp = source.codePointAt(pos);
                pos += Character.charCount(cp);
                return new ClassAtom(classLiteral(cp), false);
            }
            if (!has(1)) {
                throw invalid("\\ at end of pattern");
            }
            return switch (source.charAt(pos + 1)) {
                case 'd', 'D' -> {
                    final var text = "\\" + source.charAt(pos + 1);
                    pos += 2;
                    yield new ClassAtom(text, true);
                }
                case 'w', 'W' -> {
                    final var text = wordClass(source.charAt(pos + 1) == 'W');
                    pos += 2;
                    yield new ClassAtom(text, true);
                }
                case 's' -> {
                    pos += 2;
                    yield new ClassAtom("[" + WHITESPACE_BODY + "]", true);
                }
                case 'S' -> {
                    pos += 2;
                    yield new ClassAtom("[^" + WHITESPACE_BODY + "]", true);
                }
                case 'p', 'P' -> readClassProperty();
                default -> new ClassAtom(readCharacterEscape(true), false);
            };
        }

        private ClassAtom readClassProperty() {
            final var kind = source.charAt(pos + 1);
            if (!unicode) {
                pos += 2;
                return new ClassAtom("\\x{" + Integer.toHexString(kind) + "}", false);
            }
            final var close = propertyBodyEnd(kind);
            final var body = source.substring(pos + 3, close);
            if (StringProperties.has(body)) {
                throw invalid("property of strings '" + body + "' requires the v flag and may not be negated");
            }
            final var text = "\\" + kind + "{" + translateProperty(body) + "}";
            pos = close + 1;
            return new ClassAtom(text, true);
        }

        private static String classLiteral(int codePoint) {
            if (codePoint < 0x80 && "\\]^-[&".indexOf((char) codePoint) < 0) {
                return String.valueOf((char) codePoint);
            }
            return "\\x{" + Integer.toHexString(codePoint) + "}";
        }

        private ClassSet parseSetClass() {
            pos++;
            final var negated = pos < source.length() && peek() == '^';
            if (negated) {
                pos++;
            }
            final var operands = new ArrayList<ClassSet>();
            var operator = "";
            var current = new ClassSet();
            while (pos < source.length() && peek() != ']') {
                if (startsWith("&&") || startsWith("--")) {
                    operator = pushOperand(operands, current, operator, source.substring(pos, pos + 2));
                    current = new ClassSet();
                    pos += 2;
                    continue;
                }
                appendSetMember(current);
            }
            expect(']');
            if (!operator.isEmpty() && current.isEmpty()) {
                throw invalid("invalid set operation");
            }
            operands.add(current);
            return combine(negated, operator, operands);
        }

        private String pushOperand(List<ClassSet> operands, ClassSet current, String existing, String operator) {
            if (current.isEmpty()) {
                throw invalid("invalid set operation");
            }
            if (!existing.isEmpty() && !existing.equals(operator)) {
                throw invalid("cannot mix set operators in a character class");
            }
            operands.add(current);
            return operator;
        }

        private void appendSetMember(ClassSet target) {
            final var c = peek();
            if (c == '[') {
                merge(target, parseSetClass());
                return;
            }
            if (c == '\\' && has(1) && source.charAt(pos + 1) == 'q') {
                readStringAlternatives(target);
                return;
            }
            if (c == '\\' && has(1) && (source.charAt(pos + 1) == 'p' || source.charAt(pos + 1) == 'P')
                    && stringProperty(target)) {
                return;
            }
            if (c == '\\') {
                final var atom = readClassAtom();
                appendSetRange(target, atom);
                return;
            }
            if (SET_SYNTAX_CHARACTERS.indexOf(c) >= 0) {
                throw invalid("'" + c + "' must be escaped in a unicodeSets character class");
            }
            if (has(1) && source.charAt(pos + 1) == c && RESERVED_DOUBLE_PUNCTUATORS.indexOf(c) >= 0) {
                throw invalid("reserved double punctuator '" + c + c + "'");
            }
            final var cp = source.codePointAt(pos);
            pos += Character.charCount(cp);
            appendSetRange(target, new ClassAtom(classLiteral(cp), false));
        }

        private boolean stringProperty(ClassSet target) {
            final var kind = source.charAt(pos + 1);
            final var body = source.substring(pos + 3, propertyBodyEnd(kind));
            if (!StringProperties.has(body)) {
                return false;
            }
            pos += 4 + body.length();
            merge(target, stringPropertySet(body, kind));
            if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']' && !startsWith("--")) {
                throw invalid("invalid character class range");
            }
            return true;
        }

        private void appendSetRange(ClassSet target, ClassAtom low) {
            if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']' && !startsWith("--")) {
                pos++;
                final var high = readClassAtom();
                if (low.set() || high.set()) {
                    throw invalid("invalid character class range");
                }
                target.chars.append(low.text()).append('-').append(high.text());
                return;
            }
            target.chars.append(low.text());
        }

        // The alternatives are ClassSetCharacters, so `\q{9️⃣}` is three code points, not
        // twelve source characters: each has to be decoded before its length can be judged.
        private void readStringAlternatives(ClassSet target) {
            if (!has(2) || source.charAt(pos + 2) != '{') {
                throw invalid("\\q must be followed by '{'");
            }
            pos += 3;
            var current = new StringBuilder();
            while (pos < source.length() && peek() != '}') {
                if (peek() == '|') {
                    flushAlternative(target, current);
                    current = new StringBuilder();
                    pos++;
                    continue;
                }
                current.appendCodePoint(readSetCharacter());
            }
            if (pos >= source.length()) {
                throw invalid("unterminated \\q{ string literal");
            }
            pos++;
            flushAlternative(target, current);
        }

        private static void flushAlternative(ClassSet target, StringBuilder alternative) {
            final var text = alternative.toString();
            if (text.codePointCount(0, text.length()) == 1) {
                target.chars.append(classLiteral(text.codePointAt(0)));
            } else {
                target.strings.add(text);
            }
        }

        private int readSetCharacter() {
            if (peek() != '\\') {
                final var cp = source.codePointAt(pos);
                pos += Character.charCount(cp);
                return cp;
            }
            if (!has(1)) {
                throw invalid("\\ at end of pattern");
            }
            final var c = source.charAt(pos + 1);
            switch (c) {
                case 'n', 'r', 't', 'f', 'v', 'b', '0' -> pos += 2;
                case 'x' -> {
                    if (hexRun(pos + 2, 2) != 2) {
                        throw invalid("invalid hexadecimal escape");
                    }
                    final var value = Integer.parseInt(source.substring(pos + 2, pos + 4), 16);
                    pos += 4;
                    return value;
                }
                case 'u' -> {
                    return readUnicodeCodePoint();
                }
                case 'c' -> {
                    if (!has(2) || !Character.isLetter(source.charAt(pos + 2))) {
                        throw invalid("invalid control escape");
                    }
                    final var value = source.charAt(pos + 2) % 32;
                    pos += 3;
                    return value;
                }
                default -> {
                    if (SET_SYNTAX_CHARACTERS.indexOf(c) < 0 && RESERVED_DOUBLE_PUNCTUATORS.indexOf(c) < 0) {
                        throw invalid("invalid escape '\\" + c + "' in \\q{}");
                    }
                    pos += 2;
                    return c;
                }
            }
            return switch (c) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'f' -> '\f';
                case 'v' -> 0x0B;
                case 'b' -> 0x08;
                default -> 0;
            };
        }

        private int readUnicodeCodePoint() {
            if (has(2) && source.charAt(pos + 2) == '{') {
                final var close = source.indexOf('}', pos + 3);
                if (close < 0 || close == pos + 3) {
                    throw invalid("invalid unicode escape");
                }
                final var value = Integer.parseInt(source.substring(pos + 3, close), 16);
                if (value > Character.MAX_CODE_POINT) {
                    throw invalid("unicode escape out of range");
                }
                pos = close + 1;
                return value;
            }
            if (hexRun(pos + 2, 4) != 4) {
                throw invalid("invalid unicode escape");
            }
            final var high = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
            pos += 6;
            if (Character.isHighSurrogate((char) high) && startsWith("\\u") && hexRun(pos + 2, 4) == 4) {
                final var low = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
                if (Character.isLowSurrogate((char) low)) {
                    pos += 6;
                    return Character.toCodePoint((char) high, (char) low);
                }
            }
            return high;
        }

        private static String quoteAlternative(String text) {
            final var out = new StringBuilder();
            text.codePoints().forEach(cp -> out.append("\\x{").append(Integer.toHexString(cp)).append('}'));
            return out.toString();
        }

        private static void merge(ClassSet target, ClassSet other) {
            target.chars.append(other.chars.isEmpty() ? "" : "[" + other.chars + "]");
            target.strings.addAll(other.strings);
        }

        private static ClassSet combine(boolean negated, String operator, List<ClassSet> operands) {
            final var result = operator.isEmpty()
                    ? operands.getFirst()
                    : "&&".equals(operator) ? intersect(operands) : subtract(operands);
            if (negated) {
                if (!result.strings.isEmpty()) {
                    throw invalid("a negated class may not contain strings");
                }
                final var flipped = new ClassSet();
                flipped.chars.append('^').append(result.chars.isEmpty() ? "\\s\\S" : result.chars.toString());
                return flipped;
            }
            return result;
        }

        private static ClassSet intersect(List<ClassSet> operands) {
            final var result = new ClassSet();
            for (var i = 0; i < operands.size(); i++) {
                result.chars.append(i == 0 ? "" : "&&").append('[').append(body(operands.get(i))).append(']');
            }
            result.strings.addAll(operands.getFirst().strings);
            for (final var operand : operands.subList(1, operands.size())) {
                result.strings.retainAll(operand.strings);
            }
            return result;
        }

        private static ClassSet subtract(List<ClassSet> operands) {
            final var result = new ClassSet();
            final var removed = new StringBuilder();
            for (final var operand : operands.subList(1, operands.size())) {
                removed.append(operand.chars);
            }
            result.chars.append('[').append(body(operands.getFirst())).append("]&&")
                    .append(removed.isEmpty() ? ANY_CLASS : "[^" + removed + "]");
            result.strings.addAll(operands.getFirst().strings);
            for (final var operand : operands.subList(1, operands.size())) {
                result.strings.removeAll(operand.strings);
            }
            return result;
        }

        // An operand contributing only \q{} strings has no code points at all, which java cannot
        // spell as an empty class body.
        private static String body(ClassSet operand) {
            return operand.chars.isEmpty() ? "^\\s\\S" : operand.chars.toString();
        }

        // Strings longest-first so the alternation prefers the longest alternative, as a character
        // class would; a lone character set still renders as a plain class.
        private static String renderSet(ClassSet set) {
            if (set.strings.isEmpty()) {
                return set.chars.isEmpty()
                        ? EMPTY_CLASS
                        : "^\\s\\S".contentEquals(set.chars) ? ANY_CLASS : "[" + set.chars + "]";
            }
            final var ordered = new ArrayList<>(new LinkedHashSet<>(set.strings));
            ordered.sort((left, right) -> Integer.compare(right.codePointCount(0, right.length()),
                    left.codePointCount(0, left.length())));
            final var out = new StringBuilder("(?:");
            for (final var alternative : ordered) {
                out.append(quoteAlternative(alternative)).append('|');
            }
            if (set.chars.isEmpty()) {
                out.setLength(out.length() - 1);
            } else {
                out.append('[').append(set.chars).append(']');
            }
            return out.append(')').toString();
        }
    }
}
