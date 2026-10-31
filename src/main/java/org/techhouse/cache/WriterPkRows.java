package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.PointScannedRows;

public final class WriterPkRows {
    private final List<PkIndexEntry> whole;
    private final Map<String, PkIndexEntry> scanned;
    private final List<PkIndexEntry> vacuumRows;

    private WriterPkRows(List<PkIndexEntry> whole, Map<String, PkIndexEntry> scanned, List<PkIndexEntry> vacuumRows) {
        this.whole = whole;
        this.scanned = scanned;
        this.vacuumRows = vacuumRows;
    }

    public static WriterPkRows of(List<PkIndexEntry> whole) {
        return new WriterPkRows(whole, null, whole);
    }

    static WriterPkRows scanned(Map<String, PkIndexEntry> scanned) {
        return new WriterPkRows(null, scanned, PointScannedRows.INSTANCE);
    }

    public PkIndexEntry row(String id) {
        return whole != null ? PkIndexList.rowOf(whole, id) : scanned.get(id);
    }

    public List<PkIndexEntry> editable() {
        return whole;
    }

    public List<PkIndexEntry> vacuumRows() {
        return vacuumRows;
    }
}
