package org.techhouse.fs;

import java.util.Map;
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
}
