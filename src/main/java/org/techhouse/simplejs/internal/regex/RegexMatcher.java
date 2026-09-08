package org.techhouse.simplejs.internal.regex;

public final class RegexMatcher {
    private interface Cont {
        boolean run(int pos);
    }

    private final String input;
    private final boolean unicode;
    private final RxNode root;
    private int[] starts;
    private int[] ends;
    private int matchEnd;

    private RegexMatcher(RegexProgram program, String input) {
        this.input = input;
        this.unicode = program.unicode();
        this.root = program.root();
        this.starts = new int[program.groupCount() + 1];
        this.ends = new int[program.groupCount() + 1];
    }

    public static RegexMatch exec(RegexProgram program, String input, int fromIndex, boolean sticky) {
        final var engine = new RegexMatcher(program, input);
        var start = Math.max(fromIndex, 0);
        while (start <= input.length()) {
            engine.resetCaptures();
            if (engine.tryMatchAt(start)) {
                return engine.buildResult(start);
            }
            if (sticky) {
                return null;
            }
            start += engine.unicode && start + 1 < input.length() && Character.isHighSurrogate(input.charAt(start))
                    && Character.isLowSurrogate(input.charAt(start + 1)) ? 2 : 1;
        }
        return null;
    }

    private void resetCaptures() {
        java.util.Arrays.fill(starts, -1);
        java.util.Arrays.fill(ends, -1);
        matchEnd = -1;
    }

    private boolean tryMatchAt(int start) {
        starts[0] = start;
        return match(root, start, 1, end -> {
            matchEnd = end;
            return true;
        });
    }

    private RegexMatch buildResult(int start) {
        ends[0] = matchEnd;
        starts[0] = start;
        return new RegexMatch(starts.clone(), ends.clone(), input);
    }

    private boolean match(RxNode node, int pos, int dir, Cont k) {
        if (isSimple(node)) {
            final var next = tryConsumeSimple(node, pos, dir);
            return next >= 0 && k.run(next);
        }
        return switch (node) {
            case RxNode.Sequence(var terms) -> matchSequence(terms, dir > 0 ? 0 : terms.size() - 1, pos, dir, k);
            case RxNode.Alternation(var branches) -> matchAlternation(branches, pos, dir, k);
            case RxNode.Group(var number, var body) -> matchGroup(number, body, pos, dir, k);
            case RxNode.Quantifier(var atom, var min, var max, var greedy, var nestedGroups) ->
                matchQuantifier(atom, min, max, greedy, nestedGroups, pos, dir, k);
            case RxNode.Lookaround(var behind, var negate, var body) -> matchLookaround(behind, negate, body, pos, k);
            default -> throw new IllegalStateException("unreachable: " + node);
        };
    }

    private static boolean isSimple(RxNode node) {
        return node instanceof RxNode.CharClass || node instanceof RxNode.Literal || node instanceof RxNode.Assertion
                || node instanceof RxNode.WordBoundary || node instanceof RxNode.Backreference;
    }

    private int tryConsumeSimple(RxNode node, int pos, int dir) {
        return switch (node) {
            case RxNode.CharClass(var set) -> consumeCharClass(set, pos, dir);
            case RxNode.Literal literal ->
                matchLiteralText(literal.text(), pos, dir, literal.ignoreCase(), literal.unicode());
            case RxNode.Assertion(var kind) -> matchAssertion(kind, pos) ? pos : -1;
            case RxNode.WordBoundary(var wordSet, var negate) -> matchWordBoundary(wordSet, negate, pos) ? pos : -1;
            case RxNode.Backreference ref -> consumeBackreference(ref, pos, dir);
            default -> throw new IllegalStateException("not simple: " + node);
        };
    }

    private boolean matchSequence(java.util.List<RxNode> terms, int index, int pos, int dir, Cont k) {
        var i = index;
        var p = pos;
        while ((dir > 0 ? i < terms.size() : i >= 0) && isSimple(terms.get(i))) {
            final var next = tryConsumeSimple(terms.get(i), p, dir);
            if (next < 0) {
                return false;
            }
            p = next;
            i += dir;
        }
        if (dir > 0 ? i >= terms.size() : i < 0) {
            return k.run(p);
        }
        final var resumeAt = i + dir;
        final var pAtChoicePoint = p;
        return match(terms.get(i), pAtChoicePoint, dir, p2 -> matchSequence(terms, resumeAt, p2, dir, k));
    }

    private boolean matchAlternation(java.util.List<RxNode> branches, int pos, int dir, Cont k) {
        for (final var branch : branches) {
            if (match(branch, pos, dir, k)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchGroup(int number, RxNode body, int pos, int dir, Cont k) {
        final var savedStart = starts[number];
        final var savedEnd = ends[number];
        final var result = match(body, pos, dir, p2 -> {
            starts[number] = dir > 0 ? pos : p2;
            ends[number] = dir > 0 ? p2 : pos;
            if (k.run(p2)) {
                return true;
            }
            starts[number] = savedStart;
            ends[number] = savedEnd;
            return false;
        });
        if (!result) {
            starts[number] = savedStart;
            ends[number] = savedEnd;
        }
        return result;
    }

    private boolean matchQuantifier(RxNode atom, int min, int max, boolean greedy, int[] nestedGroups, int pos, int dir,
            Cont k) {
        if (isSimple(atom)) {
            return matchSimpleQuantifier(atom, min, max, greedy, pos, dir, k);
        }
        return repeatMatcher(atom, min, max, greedy, nestedGroups, pos, dir, k, new java.util.HashSet<>());
    }

    private record MemoKey(int pos, int min, int max) {
    }

    private boolean matchSimpleQuantifier(RxNode atom, int min, int max, boolean greedy, int pos, int dir, Cont k) {
        final var maxCount = max == RxNode.Quantifier.UNBOUNDED ? Integer.MAX_VALUE : max;
        final var reachedEnds = new java.util.ArrayList<Integer>();
        reachedEnds.add(pos);
        var p = pos;
        while (reachedEnds.size() - 1 < maxCount) {
            final var count = reachedEnds.size() - 1;
            final var next = tryConsumeSimple(atom, p, dir);
            if (next < 0) {
                break;
            }
            if (count >= min && next == p) {
                break;
            }
            p = next;
            reachedEnds.add(p);
        }
        final var reached = reachedEnds.size() - 1;
        if (reached < min) {
            return false;
        }
        if (greedy) {
            for (var count = reached; count >= min; count--) {
                if (k.run(reachedEnds.get(count))) {
                    return true;
                }
            }
        } else {
            for (var count = min; count <= reached; count++) {
                if (k.run(reachedEnds.get(count))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean repeatMatcher(RxNode atom, int min, int max, boolean greedy, int[] nestedGroups, int pos, int dir,
            Cont k, java.util.Set<MemoKey> failed) {
        if (max == 0) {
            return k.run(pos);
        }
        final var key = new MemoKey(pos, min, max);
        if (failed.contains(key)) {
            return false;
        }
        if (attemptRepeat(atom, min, max, greedy, nestedGroups, pos, dir, k, failed)) {
            return true;
        }
        failed.add(key);
        return false;
    }

    private boolean attemptRepeat(RxNode atom, int min, int max, boolean greedy, int[] nestedGroups, int pos, int dir,
            Cont k, java.util.Set<MemoKey> failed) {
        final var nextMin = min == 0 ? 0 : min - 1;
        final var nextMax = max == RxNode.Quantifier.UNBOUNDED ? RxNode.Quantifier.UNBOUNDED : max - 1;
        final var minIsZero = min == 0;
        final Cont continued = y -> {
            if (minIsZero && y == pos) {
                return false;
            }
            return repeatMatcher(atom, nextMin, nextMax, greedy, nestedGroups, y, dir, k, failed);
        };
        if (min != 0) {
            return matchIteration(atom, nestedGroups, pos, dir, continued);
        }
        if (!greedy) {
            if (k.run(pos)) {
                return true;
            }
            return matchIteration(atom, nestedGroups, pos, dir, continued);
        }
        if (matchIteration(atom, nestedGroups, pos, dir, continued)) {
            return true;
        }
        return k.run(pos);
    }

    private boolean matchIteration(RxNode atom, int[] nestedGroups, int pos, int dir, Cont k) {
        if (nestedGroups.length == 0) {
            return match(atom, pos, dir, k);
        }
        final var savedStarts = new int[nestedGroups.length];
        final var savedEnds = new int[nestedGroups.length];
        for (var i = 0; i < nestedGroups.length; i++) {
            final var number = nestedGroups[i];
            savedStarts[i] = starts[number];
            savedEnds[i] = ends[number];
            starts[number] = -1;
            ends[number] = -1;
        }
        final var result = match(atom, pos, dir, k);
        if (!result) {
            for (var i = 0; i < nestedGroups.length; i++) {
                starts[nestedGroups[i]] = savedStarts[i];
                ends[nestedGroups[i]] = savedEnds[i];
            }
        }
        return result;
    }

    private boolean matchLookaround(boolean behind, boolean negate, RxNode body, int pos, Cont k) {
        final var lookDir = behind ? -1 : 1;
        final var savedStarts = starts.clone();
        final var savedEnds = ends.clone();
        final var matched = match(body, pos, lookDir, _ -> true);
        if (negate) {
            starts = savedStarts;
            ends = savedEnds;
            return !matched && k.run(pos);
        }
        if (!matched) {
            starts = savedStarts;
            ends = savedEnds;
            return false;
        }
        if (k.run(pos)) {
            return true;
        }
        starts = savedStarts;
        ends = savedEnds;
        return false;
    }

    private int consumeCharClass(CodePointSet set, int pos, int dir) {
        if (dir > 0) {
            if (pos >= input.length()) {
                return -1;
            }
            final var c = input.charAt(pos);
            final int codePoint;
            final int width;
            if (unicode && Character.isHighSurrogate(c) && pos + 1 < input.length()
                    && Character.isLowSurrogate(input.charAt(pos + 1))) {
                codePoint = Character.toCodePoint(c, input.charAt(pos + 1));
                width = 2;
            } else {
                codePoint = c;
                width = 1;
            }
            return set.contains(codePoint) ? pos + width : -1;
        }
        if (pos <= 0) {
            return -1;
        }
        final var c = input.charAt(pos - 1);
        final int codePoint;
        final int width;
        if (unicode && Character.isLowSurrogate(c) && pos - 2 >= 0
                && Character.isHighSurrogate(input.charAt(pos - 2))) {
            codePoint = Character.toCodePoint(input.charAt(pos - 2), c);
            width = 2;
        } else {
            codePoint = c;
            width = 1;
        }
        return set.contains(codePoint) ? pos - width : -1;
    }

    private int matchLiteralText(String text, int pos, int dir, boolean ignoreCase, boolean textUnicode) {
        final var len = text.length();
        if (dir > 0) {
            if (pos + len > input.length()) {
                return -1;
            }
            for (var i = 0; i < len; i++) {
                if (charIsNotEqual(text.charAt(i), input.charAt(pos + i), ignoreCase, textUnicode)) {
                    return -1;
                }
            }
            return pos + len;
        }
        if (pos - len < 0) {
            return -1;
        }
        for (var i = 0; i < len; i++) {
            if (charIsNotEqual(text.charAt(i), input.charAt(pos - len + i), ignoreCase, textUnicode)) {
                return -1;
            }
        }
        return pos - len;
    }

    private static boolean charIsNotEqual(char a, char b, boolean ignoreCase, boolean unicode) {
        if (a == b) {
            return false;
        }
        if (!ignoreCase) {
            return true;
        }
        for (final var equivalent : CaseFold.equivalents(a, unicode)) {
            if (equivalent == b) {
                return false;
            }
        }
        return true;
    }

    private boolean matchAssertion(RxNode.Assertion.Kind kind, int pos) {
        return switch (kind) {
            case INPUT_START -> pos == 0;
            case INPUT_END -> pos == input.length();
            case LINE_START -> pos == 0 || isLineTerminator(input.charAt(pos - 1));
            case LINE_END -> pos == input.length() || isLineTerminator(input.charAt(pos));
        };
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    private boolean matchWordBoundary(CodePointSet wordSet, boolean negate, int pos) {
        final var before = pos > 0 && wordSet.contains(input.charAt(pos - 1));
        final var after = pos < input.length() && wordSet.contains(input.charAt(pos));
        final var boundary = before != after;
        return negate != boundary;
    }

    private int consumeBackreference(RxNode.Backreference ref, int pos, int dir) {
        for (final var groupNumber : ref.groupNumbers()) {
            if (starts[groupNumber] >= 0) {
                return matchLiteralText(input.substring(starts[groupNumber], ends[groupNumber]), pos, dir,
                        ref.ignoreCase(), ref.unicode());
            }
        }
        return pos;
    }
}
