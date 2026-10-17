package org.techhouse.ops;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonObject;

public final class ConjunctionTally {
    private final Map<Object, Tally> tallies = new HashMap<>();

    public static Object keyOf(JsonObject row) {
        final var id = row.get(Globals.PK_FIELD);
        return id != null ? id : row;
    }

    public void add(JsonObject row) {
        tallies.computeIfAbsent(keyOf(row), _ -> new Tally(row)).count++;
    }

    public int size() {
        return tallies.size();
    }

    public Stream<JsonObject> rowsCounted(int count) {
        return tallies.values().stream().filter(tally -> tally.count == count).map(tally -> tally.first);
    }

    private static final class Tally {
        private final JsonObject first;
        private int count;

        private Tally(JsonObject first) {
            this.first = first;
        }
    }
}
