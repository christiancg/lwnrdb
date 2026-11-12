package org.techhouse.ops.snapshot;

import java.io.IOException;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.techhouse.fs.RetiredCopies;
import org.techhouse.fs.SnapshotImageFile;

final class CollectionImages {
    private final SnapshotImageFile file;
    private final ConcurrentSkipListMap<String, long[]> images = new ConcurrentSkipListMap<>();
    private final Set<String> bornAfter = ConcurrentHashMap.newKeySet();

    CollectionImages(SnapshotImageFile file) {
        this.file = file;
    }

    long preserve(RetiredCopies.RetiredCopy copy, RetiredCopies.OldLines oldLines) throws IOException {
        final var id = copy.id();
        if (images.containsKey(id) || bornAfter.contains(id)) {
            return 0L;
        }
        final var line = oldLines.read(copy);
        final var offset = file.append(line);
        images.put(id, new long[]{offset, line.length});
        return line.length + SnapshotCharges.perId(id);
    }

    long born(String id) {
        if (images.containsKey(id) || !bornAfter.add(id)) {
            return 0L;
        }
        return SnapshotCharges.perId(id);
    }

    boolean hasImage(String id) {
        return images.containsKey(id);
    }

    byte[] imageOf(String id) throws IOException {
        final var location = images.get(id);
        return file.read(location[0], Math.toIntExact(location[1]));
    }

    boolean isBornAfter(String id) {
        return bornAfter.contains(id);
    }

    NavigableSet<String> imageIdsAfter(String afterId) {
        return afterId == null ? images.navigableKeySet() : images.tailMap(afterId, false).navigableKeySet();
    }

    void closeFile() throws IOException {
        file.close();
    }
}
