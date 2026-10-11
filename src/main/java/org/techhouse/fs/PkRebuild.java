package org.techhouse.fs;

import java.util.List;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;

public record PkRebuild(List<PkIndexEntry> rows, int unversioned, boolean previousReadable, List<DbEntry> appeared,
        List<PkIndexEntry> vanished) {
    public int entries() {
        return rows.size();
    }
}
