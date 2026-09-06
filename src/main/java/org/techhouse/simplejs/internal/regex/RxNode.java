package org.techhouse.simplejs.internal.regex;

import java.util.List;

/**
 * AST for a compiled ECMA-262 {@code Pattern}, matched directly by {@link RegexMatcher} (no
 * translation to {@code java.util.regex} syntax). A non-capturing group {@code (?:...)} is not a
 * node at all - the parser folds its body straight into the surrounding structure - so every
 * {@link Group} is capturing.
 */
sealed interface RxNode {
    record Sequence(List<RxNode> terms) implements RxNode {
    }

    record Alternation(List<RxNode> branches) implements RxNode {
    }

    record CharClass(CodePointSet set) implements RxNode {
    }

    record Literal(String text, boolean ignoreCase, boolean unicode) implements RxNode {
    }

    record Assertion(Kind kind) implements RxNode {
        enum Kind {
            INPUT_START, INPUT_END, LINE_START, LINE_END
        }
    }

    record WordBoundary(CodePointSet wordSet, boolean negate) implements RxNode {
    }

    record Backreference(int[] groupNumbers, boolean ignoreCase, boolean unicode) implements RxNode {
    }

    record Group(int number, RxNode body) implements RxNode {
    }

    // nestedGroups: every capturing group number inside `atom`, computed once at construction time.
    // ECMA-262's RepeatMatcher resets exactly these to undefined before each new iteration attempt -
    // without it, a group from an earlier iteration that doesn't participate in a later one keeps
    // reporting its stale value instead of undefined.
    record Quantifier(RxNode atom, int min, int max, boolean greedy, int[] nestedGroups) implements RxNode {
        static final int UNBOUNDED = -1;

        Quantifier(RxNode atom, int min, int max, boolean greedy) {
            this(atom, min, max, greedy, capturingGroupsIn(atom));
        }
    }

    record Lookaround(boolean behind, boolean negate, RxNode body) implements RxNode {
    }

    static int[] capturingGroupsIn(RxNode node) {
        final var found = new java.util.ArrayList<Integer>();
        collectGroups(node, found);
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void collectGroups(RxNode node, List<Integer> out) {
        switch (node) {
            case Sequence(var terms) -> {
                for (final var term : terms) {
                    collectGroups(term, out);
                }
            }
            case Alternation(var branches) -> {
                for (final var branch : branches) {
                    collectGroups(branch, out);
                }
            }
            case Group(var number, var body) -> {
                out.add(number);
                collectGroups(body, out);
            }
            case Quantifier(var _, var _, var _, var _, var nestedGroups) -> {
                for (final var number : nestedGroups) {
                    out.add(number);
                }
            }
            case Lookaround(var _, var _, var body) -> collectGroups(body, out);
            default -> {
                // CharClass, Literal, Assertion, WordBoundary, Backreference: no nested groups.
            }
        }
    }
}
