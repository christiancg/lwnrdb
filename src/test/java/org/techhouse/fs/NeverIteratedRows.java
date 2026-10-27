package org.techhouse.fs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.techhouse.data.PkIndexEntry;

final class NeverIteratedRows extends ArrayList<PkIndexEntry> {
    NeverIteratedRows(List<PkIndexEntry> rows) {
        super(rows);
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public Iterator<PkIndexEntry> iterator() {
        throw new AssertionError("the resident rows were walked");
    }
}
