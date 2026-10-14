package org.techhouse.fs;

import org.techhouse.data.PkIndexEntry;

record PkChange(String removedId, PkIndexEntry replacement) {
    static PkChange delete(String removedId) {
        return new PkChange(removedId, null);
    }

    static PkChange update(PkIndexEntry replacement) {
        return new PkChange(replacement.getValue(), replacement);
    }
}
