package org.techhouse.simplejs.internal.regex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable set of Unicode code points, stored as sorted, non-overlapping inclusive ranges.
 */
public final class CodePointSet {
    public static final int MAX_CODE_POINT = 0x10FFFF;
    public static final CodePointSet EMPTY = new CodePointSet(new int[0]);
    public static final CodePointSet ALL = CodePointSet.of(0, MAX_CODE_POINT);

    // ranges[2i] = lo, ranges[2i+1] = hi (inclusive), sorted and non-overlapping/non-adjacent.
    private final int[] ranges;

    private CodePointSet(int[] ranges) {
        this.ranges = ranges;
    }

    public static CodePointSet of(int lo, int hi) {
        if (hi < lo) {
            return EMPTY;
        }
        return new CodePointSet(new int[]{lo, hi});
    }

    public static CodePointSet ofChar(int cp) {
        return of(cp, cp);
    }

    public boolean isEmpty() {
        return ranges.length == 0;
    }

    public boolean contains(int cp) {
        var lo = 0;
        var hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            final var mid = (lo + hi) >>> 1;
            final var rangeLo = ranges[mid * 2];
            final var rangeHi = ranges[mid * 2 + 1];
            if (cp < rangeLo) {
                hi = mid - 1;
            } else if (cp > rangeHi) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    public CodePointSet negate() {
        return negate(0, MAX_CODE_POINT);
    }

    public CodePointSet negate(int lo, int hi) {
        final var out = new Builder();
        var cursor = lo;
        for (var i = 0; i < ranges.length; i += 2) {
            final var rLo = Math.max(ranges[i], lo);
            final var rHi = Math.min(ranges[i + 1], hi);
            if (rHi < rLo) {
                continue;
            }
            if (cursor < rLo) {
                out.addRange(cursor, rLo - 1);
            }
            cursor = Math.max(cursor, rHi + 1);
        }
        if (cursor <= hi) {
            out.addRange(cursor, hi);
        }
        return out.build();
    }

    public CodePointSet union(CodePointSet other) {
        final var out = new Builder();
        out.addSet(this);
        out.addSet(other);
        return out.build();
    }

    public CodePointSet intersect(CodePointSet other) {
        final var out = new Builder();
        var i = 0;
        var j = 0;
        while (i < ranges.length && j < other.ranges.length) {
            final var lo = Math.max(ranges[i], other.ranges[j]);
            final var hi = Math.min(ranges[i + 1], other.ranges[j + 1]);
            if (lo <= hi) {
                out.addRange(lo, hi);
            }
            if (ranges[i + 1] < other.ranges[j + 1]) {
                i += 2;
            } else {
                j += 2;
            }
        }
        return out.build();
    }

    public CodePointSet subtract(CodePointSet other) {
        return intersect(other.negate());
    }

    public static final class Builder {
        private final List<int[]> pending = new ArrayList<>();

        public Builder addRange(int lo, int hi) {
            if (hi >= lo) {
                pending.add(new int[]{lo, hi});
            }
            return this;
        }

        public Builder addChar(int cp) {
            return addRange(cp, cp);
        }

        public Builder addSet(CodePointSet set) {
            for (var i = 0; i < set.ranges.length; i += 2) {
                pending.add(new int[]{set.ranges[i], set.ranges[i + 1]});
            }
            return this;
        }

        public CodePointSet build() {
            if (pending.isEmpty()) {
                return EMPTY;
            }
            pending.sort(Comparator.comparingInt(a -> a[0]));
            final var merged = new ArrayList<int[]>();
            var current = pending.getFirst();
            for (var i = 1; i < pending.size(); i++) {
                final var next = pending.get(i);
                if (next[0] <= current[1] + 1) {
                    current = new int[]{current[0], Math.max(current[1], next[1])};
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
            final var out = new int[merged.size() * 2];
            for (var i = 0; i < merged.size(); i++) {
                out[i * 2] = merged.get(i)[0];
                out[i * 2 + 1] = merged.get(i)[1];
            }
            return new CodePointSet(out);
        }
    }
}
