package org.techhouse.simplejs.internal.regex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CaseFold {
    private static final int[][] EXTRA_UNICODE_ONLY_PAIRS = {{0x0390, 0x1FD3}, {0x03B0, 0x1FE3}, {0xFB05, 0xFB06}};

    private static final Map<Integer, int[]> UNICODE_CLOSURE = buildClosure(true);
    private static final Map<Integer, int[]> RESTRICTED_CLOSURE = buildClosure(false);

    private CaseFold() {
    }

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
