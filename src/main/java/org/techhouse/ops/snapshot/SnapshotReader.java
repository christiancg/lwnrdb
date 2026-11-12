package org.techhouse.ops.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class SnapshotReader {
    private static final int CANDIDATES_HINT = 1024;
    private static final Cache cache = IocContainer.get(Cache.class);

    public record Page(List<JsonObject> results, String lastId, boolean done) {
    }

    private SnapshotReader() {
    }

    public static Page read(Snapshot snapshot, String collName, String afterId, int limit) throws IOException {
        final var dbName = snapshot.dbName();
        final var loaded = cache.getPkIndexAndLoadIfNecessary(dbName, collName);
        final List<PkIndexEntry> rows = loaded == null ? List.of() : loaded;
        final var images = snapshot.existingImagesOf(collName);
        final Iterator<String> imageIds = images == null
                ? Collections.emptyIterator()
                : images.imageIdsAfter(afterId).iterator();
        var rowIndex = afterId == null ? 0 : firstAfter(rows, afterId);
        var nextImage = imageIds.hasNext() ? imageIds.next() : null;
        final var candidates = new ArrayList<String>(Math.min(limit, CANDIDATES_HINT));
        while (candidates.size() < limit && (rowIndex < rows.size() || nextImage != null)) {
            final var rowId = rowIndex < rows.size() ? rows.get(rowIndex).getValue() : null;
            final var order = orderOf(rowId, nextImage);
            if (order <= 0) {
                candidates.add(rowId);
                rowIndex++;
            }
            if (order >= 0) {
                if (order > 0) {
                    candidates.add(nextImage);
                }
                nextImage = imageIds.hasNext() ? imageIds.next() : null;
            }
        }
        final var done = rowIndex >= rows.size() && nextImage == null;
        final var lastId = candidates.isEmpty() ? afterId : candidates.getLast();
        return new Page(documentsOf(dbName, collName, candidates, images), lastId, done);
    }

    private static int orderOf(String rowId, String imageId) {
        if (rowId == null) {
            return 1;
        }
        return imageId == null ? -1 : rowId.compareTo(imageId);
    }

    private static int firstAfter(List<PkIndexEntry> rows, String afterId) {
        var low = 0;
        var high = rows.size();
        while (low < high) {
            final var middle = (low + high) >>> 1;
            if (rows.get(middle).getValue().compareTo(afterId) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static List<JsonObject> documentsOf(String dbName, String collName, List<String> candidates,
            CollectionImages images) throws IOException {
        final var currentIds = new LinkedHashSet<String>();
        for (final var id : candidates) {
            if (images == null || !images.hasImage(id) && !images.isBornAfter(id)) {
                currentIds.add(id);
            }
        }
        final var current = new HashMap<String, JsonObject>();
        if (!currentIds.isEmpty()) {
            for (final var entry : cache.getEntriesByIds(dbName, collName, currentIds)) {
                current.put(entry.get_id(), entry.getData());
            }
        }
        final var documents = new ArrayList<JsonObject>(candidates.size());
        for (final var id : candidates) {
            if (images != null && images.hasImage(id)) {
                final var line = images.imageOf(id);
                documents.add(DbEntry.fromBytes(dbName, collName, line, 0, line.length).getData());
            } else if (current.containsKey(id)) {
                documents.add(current.get(id));
            }
        }
        return documents;
    }
}
