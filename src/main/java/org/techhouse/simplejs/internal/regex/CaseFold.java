package org.techhouse.simplejs.internal.regex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ECMA-262 Runtime Semantics: Canonicalize, implemented as a precomputed case-equivalence closure
 * rather than a per-character fold: {@link #equivalents} returns every code point that a given one
 * is indistinguishable from under {@code i} matching, so a compiled character class can be widened
 * once (at compile time) instead of re-folding every candidate at match time.
 *
 * <p>Two closures are kept because the equivalence relation itself differs by mode: without the
 * {@code u}/{@code v} flag, Canonicalize only follows {@link Character#toUpperCase(int)} and never
 * lets a non-ASCII code point fold down into ASCII (so e.g. LATIN SMALL LETTER LONG S does not join
 * plain {@code s}); with the flag, that restriction lifts and a handful of Unicode
 * CaseFolding.txt pairs with no ordinary upper/lower relationship at all (e.g. U+0390/U+1FD3) also
 * join.
 */
final class CaseFold {
    // CaseFolding.txt "simple"/"common" pairs with no Character.toUpperCase/toLowerCase/toTitleCase
    // relationship whatsoever - only observable under the u/v flag.
    private static final int[][] EXTRA_UNICODE_ONLY_PAIRS = {{0x0390, 0x1FD3}, {0x03B0, 0x1FE3}, {0xFB05, 0xFB06}};

    private static final Map<Integer, int[]> UNICODE_CLOSURE = buildClosure(true);
    private static final Map<Integer, int[]> RESTRICTED_CLOSURE = buildClosure(false);

    private CaseFold() {
    }

    /**
     * Widens {@code set} to include every case-equivalence partner of a code point it already
     * contains - the compile-time half of Canonicalize-based matching (see {@link #equivalents}):
     * the match-time side then needs no per-candidate folding at all, just a plain membership test.
     */
    static CodePointSet widen(CodePointSet set, boolean unicode) {
        if (set.isEmpty()) {
            return set;
        }
        final var builder = new CodePointSet.Builder().addSet(set);
        var changed = false;
        for (final var cp : participants(unicode)) {
            if (set.contains(cp)) {
                for (final var equivalent : equivalents(cp, unicode)) {
                    builder.addChar(equivalent);
                }
                changed = true;
            }
        }
        return changed ? builder.build() : set;
    }

    static int[] equivalents(int cp, boolean unicode) {
        final var closure = unicode ? UNICODE_CLOSURE : RESTRICTED_CLOSURE;
        final var found = closure.get(cp);
        return found == null ? new int[]{cp} : found;
    }

    // Every code point that has ANY case-equivalence partner, i.e. the only ones `widen` ever needs
    // to inspect - a few thousand entries rather than the full 0x110000 code point space.
    static int[] participants(boolean unicode) {
        final var closure = unicode ? UNICODE_CLOSURE : RESTRICTED_CLOSURE;
        return closure.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static Map<Integer, int[]> buildClosure(boolean unicode) {
        final var parent = new HashMap<Integer, Integer>();
        for (var cp = 0; cp <= CodePointSet.MAX_CODE_POINT; cp++) {
            union(parent, cp, Character.toUpperCase(cp), unicode);
            union(parent, cp, Character.toLowerCase(cp), unicode);
            union(parent, cp, Character.toTitleCase(cp), unicode);
        }
        if (unicode) {
            for (final var pair : EXTRA_UNICODE_ONLY_PAIRS) {
                union(parent, pair[0], pair[1], true);
            }
        }
        final var members = new HashMap<Integer, List<Integer>>();
        for (final var cp : parent.keySet()) {
            members.computeIfAbsent(find(parent, cp), _ -> new ArrayList<>()).add(cp);
        }
        final var closure = new HashMap<Integer, int[]>();
        for (final var group : members.values()) {
            if (group.size() < 2) {
                continue;
            }
            final var array = group.stream().mapToInt(Integer::intValue).sorted().toArray();
            for (final var cp : array) {
                closure.put(cp, array);
            }
        }
        return closure;
    }

    // A cross-boundary edge (one side ASCII, the other not) is only admitted under the u/v flag.
    private static void union(Map<Integer, Integer> parent, int a, int b, boolean unicode) {
        if (a == b) {
            return;
        }
        if (!unicode && (a >= 128) != (b >= 128)) {
            return;
        }
        final var rootA = find(parent, a);
        final var rootB = find(parent, b);
        if (rootA != rootB) {
            parent.put(rootA, rootB);
        }
    }

    private static int find(Map<Integer, Integer> parent, int cp) {
        var current = cp;
        while (parent.containsKey(current) && parent.get(current) != current) {
            current = parent.get(current);
        }
        parent.putIfAbsent(cp, current);
        return current;
    }
}
