package org.techhouse.fs;

import java.util.Map;
import java.util.function.Function;
import org.techhouse.data.PkIndexEntry;

public record VacuumedPage(String dbName, String collName, long page, Map<String, Move> moves) {
    public record Move(long from, long to) {
    }

    public void reposition(Iterable<PkIndexEntry> rows) {
        for (final var row : rows) {
            if (row.getPage() != page) {
                continue;
            }
            final var move = moves.get(row.getValue());
            if (move != null && row.getPosition() == move.from()) {
                row.setPosition(move.to());
            }
        }
    }

    public void reposition(Function<String, PkIndexEntry> rowOf) {
        for (final var move : moves.entrySet()) {
            final var row = rowOf.apply(move.getKey());
            if (row != null && row.getPage() == page && row.getPosition() == move.getValue().from()) {
                row.setPosition(move.getValue().to());
            }
        }
    }
}
