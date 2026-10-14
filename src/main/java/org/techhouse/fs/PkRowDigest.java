package org.techhouse.fs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class PkRowDigest {
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;
    private static final long MIX_FIRST = 0xBF58476D1CE4E5B9L;
    private static final long MIX_SECOND = 0x94D049BB133111EBL;

    private PkRowDigest() {
    }

    record Located(List<PkIndexEntry> rows, PkIndexEntry removed) {
    }

    static long of(PkIndexEntry row) {
        return of(row, row.getPosition());
    }

    static long of(PkIndexEntry row, long position) {
        final var value = row.getValue();
        var hash = FNV_OFFSET;
        for (var i = 0; i < value.length(); i++) {
            hash = (hash ^ value.charAt(i)) * FNV_PRIME;
        }
        hash = mix(hash ^ value.length());
        hash = mix(hash ^ position);
        hash = mix(hash ^ row.getLength());
        hash = mix(hash ^ row.getPage());
        return mix(hash ^ row.getVersion());
    }

    static long sum(Collection<PkIndexEntry> rows) {
        var digest = 0L;
        for (final var row : rows) {
            digest += of(row);
        }
        return digest;
    }

    static Located locate(List<PkIndexEntry> rows, String removedId, long expectedDigest) {
        var digest = 0L;
        var sorted = true;
        PkIndexEntry removed = null;
        String previous = null;
        for (final var row : rows) {
            final var value = row.getValue();
            if (previous != null && previous.compareTo(value) >= 0) {
                sorted = false;
            }
            if (value.equals(removedId)) {
                if (removed != null) {
                    return null;
                }
                removed = row;
            }
            digest += of(row);
            previous = value;
        }
        if (removed == null || digest != expectedDigest) {
            return null;
        }
        if (sorted) {
            return new Located(rows, removed);
        }
        final var copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(PkIndexEntry::getValue));
        for (var i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).getValue().equals(copy.get(i).getValue())) {
                return null;
            }
        }
        return new Located(copy, removed);
    }

    private static long mix(long value) {
        var z = (value ^ (value >>> 30)) * MIX_FIRST;
        z = (z ^ (z >>> 27)) * MIX_SECOND;
        return z ^ (z >>> 31);
    }
}
