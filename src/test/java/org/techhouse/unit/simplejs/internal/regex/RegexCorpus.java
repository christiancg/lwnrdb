package org.techhouse.unit.simplejs.internal.regex;

import java.util.List;

public final class RegexCorpus {
    public static final List<String[]> PATTERNS = List.of(new String[]{"a", ""}, new String[]{"ab+c", ""},
            new String[]{"^abc$", ""}, new String[]{"(a|ab)(c|bcd)(d*)", ""}, new String[]{"(a*)\\1b", ""},
            new String[]{"(?<=\\$)\\d+", ""}, new String[]{"(?<!\\$)\\b\\d+", ""}, new String[]{"(?=a)a", ""},
            new String[]{"(?!b).", ""}, new String[]{"[a-z]+@[a-z]+\\.com", "i"}, new String[]{"^\\s*$", "m"},
            new String[]{"\\p{L}+", "u"}, new String[]{"\\u{1F600}", "u"}, new String[]{".", "su"},
            new String[]{"(x+x+)+y", ""}, new String[]{"(?<year>\\d{4})-(?<month>\\d{2})", ""},
            new String[]{"a{2,3}?", ""}, new String[]{"(?:)", ""}, new String[]{"\\bfoo\\b", "i"},
            new String[]{"[^\\d\\s]{2,}", ""}, new String[]{"(a)|b", ""}, new String[]{"((a)|(b))+", ""},
            new String[]{"x*", ""}, new String[]{"$", ""}, new String[]{"^", "m"}, new String[]{"(?:a|b)*?c", ""},
            new String[]{"[\\u00e9\\u00f6]", "i"}, new String[]{"\\B", ""});
    public static final List<String> INPUTS = List.of("", "a", "abc", "xxabbbcxx", "abcd", "aaab", "$123 456",
            "Foo bar foo", "line1\n\nline3", "héllo wörld", "😀x😀", "xxxxxxxxxxxxxxxx!", "2026-10 and 1999-01", "aab",
            "ab ab", "\n", "ababababc", "fo foo food");

    private RegexCorpus() {
    }
}
