package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import java.util.List;
import java.util.function.Supplier;

final class IndexComparison {
    private IndexComparison() {
    }

    static List<String> ids(List<Document> rows) {
        return rows.stream().map(row -> row.id().orElse("?")).toList();
    }

    static List<String> sortedIds(List<Document> rows) {
        return ids(rows).stream().sorted().toList();
    }

    static <R> R sameWithAndWithoutIndex(Collection<Document> collection, String field, Supplier<R> query) {
        final var scanned = query.get();
        collection.indexes().create(field);
        try {
            assertEquals(scanned, query.get(), "index and scan disagree on " + field);
        } finally {
            collection.indexes().drop(field);
        }
        return scanned;
    }
}
