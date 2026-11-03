package org.techhouse.fs;

public final class TombstoneView {
    @FunctionalInterface
    public interface Visitor {
        void visit(String id, long version);
    }

    private final ResidentTombstones resident;
    private final long oldest;
    private final long highest;

    TombstoneView(ResidentTombstones resident, long oldest, long highest) {
        this.resident = resident;
        this.oldest = oldest;
        this.highest = highest;
    }

    public long versionOf(String id) {
        return resident.versionOf(id);
    }

    public void forEach(Visitor visitor) {
        resident.forEach(visitor::visit);
    }

    public int size() {
        return resident.size();
    }

    public boolean isEmpty() {
        return resident.size() == 0;
    }

    public long oldest() {
        return oldest;
    }

    public long highest() {
        return highest;
    }
}
