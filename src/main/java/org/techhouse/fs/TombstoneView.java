package org.techhouse.fs;

import java.util.Set;

public final class TombstoneView {
    @FunctionalInterface
    public interface Visitor {
        void visit(String id, long version);
    }

    private final ResidentTombstones resident;
    private final long oldest;
    private final long highest;
    private final Set<TombstoneRetract> hidden;
    private final int hiddenPresent;

    TombstoneView(ResidentTombstones resident, long oldest, long highest) {
        this(resident, oldest, highest, Set.of());
    }

    private TombstoneView(ResidentTombstones resident, long oldest, long highest, Set<TombstoneRetract> hidden) {
        this.resident = resident;
        this.oldest = oldest;
        this.highest = highest;
        this.hidden = hidden;
        this.hiddenPresent = (int) hidden.stream().filter(held -> resident.versionOf(held.id()) == held.version())
                .count();
    }

    TombstoneView without(Set<TombstoneRetract> heldBack) {
        return new TombstoneView(resident, oldest, highest, heldBack);
    }

    public long versionOf(String id) {
        final var version = resident.versionOf(id);
        return hidden.isEmpty() || !hidden.contains(new TombstoneRetract(id, version)) ? version : 0L;
    }

    public void forEach(Visitor visitor) {
        if (hidden.isEmpty()) {
            resident.forEach(visitor::visit);
            return;
        }
        resident.forEach((id, version) -> {
            if (!hidden.contains(new TombstoneRetract(id, version))) {
                visitor.visit(id, version);
            }
        });
    }

    public int size() {
        return resident.size() - hiddenPresent;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public long oldest() {
        return oldest;
    }

    public long highest() {
        return highest;
    }
}
