package org.techhouse.simplejs.internal.regex;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RegexProgram {
    private static final int NODE_BYTES = 48;
    private static final int GROUP_SLOT_BYTES = 8;
    private final RxNode root;
    private final int groupCount;
    private final Map<String, List<Integer>> groupAliases;
    private final boolean unicode;

    RegexProgram(RxNode root, int groupCount, Map<String, List<Integer>> groupAliases, boolean unicode) {
        this.root = root;
        this.groupCount = groupCount;
        this.groupAliases = groupAliases;
        this.unicode = unicode;
    }

    RxNode root() {
        return root;
    }

    int groupCount() {
        return groupCount;
    }

    public Map<String, List<Integer>> groupAliases() {
        return groupAliases;
    }

    boolean unicode() {
        return unicode;
    }

    public long estimatedBytes() {
        final var sets = Collections.<CodePointSet>newSetFromMap(new IdentityHashMap<>());
        final var pending = new ArrayDeque<RxNode>();
        pending.push(root);
        var bytes = (long) GROUP_SLOT_BYTES * (groupCount + 1);
        while (!pending.isEmpty()) {
            final var node = pending.pop();
            bytes += NODE_BYTES + ownBytes(node, sets);
            queueChildren(node, pending);
        }
        return bytes;
    }

    private static long ownBytes(RxNode node, Set<CodePointSet> sets) {
        return switch (node) {
            case RxNode.Literal literal -> literal.text().length();
            case RxNode.CharClass(var set) -> sets.add(set) ? set.estimatedBytes() : 0;
            case RxNode.WordBoundary(var wordSet, var _) -> sets.add(wordSet) ? wordSet.estimatedBytes() : 0;
            case RxNode.Backreference ref -> (long) Integer.BYTES * ref.groupNumbers().length;
            case RxNode.Quantifier quantifier -> (long) Integer.BYTES * quantifier.nestedGroups().length;
            default -> 0;
        };
    }

    private static void queueChildren(RxNode node, ArrayDeque<RxNode> pending) {
        switch (node) {
            case RxNode.Sequence(var terms) -> terms.forEach(pending::push);
            case RxNode.Alternation(var branches) -> branches.forEach(pending::push);
            case RxNode.Group(var _, var body) -> pending.push(body);
            case RxNode.Quantifier quantifier -> pending.push(quantifier.atom());
            case RxNode.Lookaround(var _, var _, var body) -> pending.push(body);
            default -> {
            }
        }
    }
}
