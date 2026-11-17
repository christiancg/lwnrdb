package org.techhouse.unit.ops.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.ops.index.IndexedValue;
import org.techhouse.utils.JsonUtils;

final class RefusedTypeWorld {
    record Event(String id, JsonBaseElement prior) {
    }

    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final FieldIndexBatchSupport support = new FieldIndexBatchSupport();
    private final String fieldName;
    private final int pool;
    private final List<Event> unprocessed = new ArrayList<>();

    RefusedTypeWorld(String fieldName, int pool) {
        this.fieldName = fieldName;
        this.pool = pool;
    }

    FieldIndexBatchSupport support() {
        return support;
    }

    List<Event> unprocessed() {
        return unprocessed;
    }

    Event write(String id, JsonBaseElement value) {
        final var event = new Event(id, current(id));
        support.put(id, value);
        unprocessed.add(event);
        return event;
    }

    Event delete(String id) {
        final var event = new Event(id, current(id));
        support.delete(id);
        unprocessed.add(event);
        return event;
    }

    private JsonBaseElement current(String id) {
        final var doc = support.documentOf(id, fieldName);
        if (doc == null || !doc.getData().has(fieldName)) {
            return JsonNull.INSTANCE;
        }
        return doc.getData().get(fieldName);
    }

    void seed(List<String> ids) throws IOException, InterruptedException {
        support.applyBatchHoldingTheCollection(fieldName, ids);
    }

    void process(List<Event> events, boolean withPriors) throws IOException, InterruptedException {
        final var ids = new LinkedHashSet<String>();
        final var priors = new HashMap<String, List<IndexedValue>>();
        for (final var event : events) {
            ids.add(event.id());
            priors.computeIfAbsent(event.id(), _ -> new ArrayList<>()).add(IndexedValue.of(event.prior()));
        }
        unprocessed.removeAll(events);
        locks.lockRead(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        locks.lockIndex(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, fieldName);
        try {
            final var batch = FieldIndexBatch.open(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, fieldName,
                    List.copyOf(ids), withPriors ? priors : null);
            for (final var id : ids) {
                final var doc = support.documentOf(id, fieldName);
                batch.apply(id, doc == null ? null : JsonUtils.getFromPath(doc.getData(), fieldName));
            }
            batch.flush();
        } finally {
            locks.releaseIndex(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL, fieldName);
            locks.releaseRead(FieldIndexBatchSupport.DB, FieldIndexBatchSupport.COLL);
        }
    }

    String mismatch() throws IOException {
        final var pending = new TreeSet<String>();
        for (final var event : unprocessed) {
            pending.add(event.id());
        }
        final var expected = expectedPlacements();
        final var actual = placementsOnDisk();
        for (var i = 0; i < pool; i++) {
            final var id = "id" + i;
            if (pending.contains(id)) {
                continue;
            }
            final var want = expected.getOrDefault(id, Set.of());
            final var have = actual.getOrDefault(id, Set.of());
            if (!want.equals(have)) {
                return id + " is indexed under " + have + " but its document holds " + want;
            }
        }
        return null;
    }

    private Map<String, Set<String>> expectedPlacements() {
        final var documents = new ArrayList<DbEntry>();
        for (var i = 0; i < pool; i++) {
            final var doc = support.documentOf("id" + i, fieldName);
            if (doc != null) {
                documents.add(doc);
            }
        }
        return placementsOf(FieldIndexBatchSupport.legacyBuildSnapshot(documents, fieldName));
    }

    private Map<String, Set<String>> placementsOnDisk() throws IOException {
        final var placements = new TreeMap<String, Set<String>>();
        for (final var type : FieldIndexBatchSupport.loadedTypes(fieldName).entrySet()) {
            for (final var entry : type.getValue()) {
                for (final var id : entry.getIds()) {
                    placements.computeIfAbsent(id, _ -> new TreeSet<>())
                            .add(type.getKey() + ":" + FieldIndexEntry.fileKeyOf(entry.getValue()));
                }
            }
        }
        return placements;
    }

    private static Map<String, Set<String>> placementsOf(Map<String, Set<String>> snapshot) {
        final var placements = new TreeMap<String, Set<String>>();
        for (final var type : snapshot.entrySet()) {
            for (final var line : type.getValue()) {
                final var split = line.indexOf(" => ");
                final var key = line.substring(0, split);
                for (final var id : line.substring(split + 4).split(",")) {
                    placements.computeIfAbsent(id, _ -> new TreeSet<>()).add(type.getKey() + ":" + key);
                }
            }
        }
        return placements;
    }
}
