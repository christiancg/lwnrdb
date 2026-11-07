package org.techhouse.cache;

import java.util.List;
import java.util.Map;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.PointScannedRows;

public final class WriterPkRows {
    private final List<PkIndexEntry> whole;
    private final Map<String, PkIndexEntry> scanned;
    private final List<PkIndexEntry> vacuumRows;
    private String lastId;
    private int lastSlot;

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
        if (whole == null) {
            return scanned.get(id);
        }
        final var at = PkIndexList.positionOf(whole, id);
        lastId = id;
        lastSlot = at;
        return at < 0 ? null : whole.get(at);
    }

    public void store(PkIndexEntry row, boolean replaces) {
        if (whole == null) {
            return;
        }
        final var at = slotOf(row.getValue());
        if (replaces) {
            whole.set(at, row);
        } else {
            whole.add(at < 0 ? -(at + 1) : at, row);
        }
    }

    public void remove(String id) {
        if (whole != null) {
            whole.remove(slotOf(id));
        }
    }

    private int slotOf(String id) {
        return id.equals(lastId) && stillHolds(id, lastSlot) ? lastSlot : PkIndexList.positionOf(whole, id);
    }

    private boolean stillHolds(String id, int slot) {
        if (slot >= 0) {
            return slot < whole.size() && whole.get(slot).getValue().equals(id);
        }
        final var insertion = -(slot + 1);
        return insertion <= whole.size() && (insertion == 0 || whole.get(insertion - 1).getValue().compareTo(id) < 0)
                && (insertion == whole.size() || whole.get(insertion).getValue().compareTo(id) > 0);
    }

    public List<PkIndexEntry> editable() {
        return whole;
    }

    public List<PkIndexEntry> vacuumRows() {
        return vacuumRows;
    }
}
