package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.RangeIdWalk;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.JsonUtils;

public final class SortOperatorHelper {
    private static final int MAX_ID_FETCH_CHUNK = 256;
    private static final int MIN_ID_FETCH_CHUNK = 32;
    private static final int MAX_BOUNDED_SORT = 4096;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private SortOperatorHelper() {
    }

    private record Keyed(JsonObject document, JsonBaseElement key, int seq) {
    }

    record Sorted(Stream<JsonObject> rows, boolean skipApplied) {
    }

    static Sorted processSortStep(SortAggregationStep sortStep, Stream<JsonObject> resultStream, String dbName,
            String collName, long bound, long skip, PipelineContext context) throws IOException {
        final var fieldName = sortStep.getFieldName();
        final var ascending = sortStep.getAscending();
        if (resultStream == null && (bound > 0 || !ScanPreference.indexFetchNotWorthIt(dbName, collName))) {
            final var orderedIds = IndexHelper.getSortedIdsForField(dbName, collName, fieldName, entryOrder(ascending),
                    bound > 0 ? bound : Long.MAX_VALUE, context);
            if (orderedIds != null) {
                return fetchSkipping(orderedIds, dbName, collName, bound, skip, context);
            }
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName, context);
            if (indexEntries != null) {
                return new Sorted(sortViaIndex(indexEntries, dbName, collName, ascending, bound, context), false);
            }
        }
        final var source = context.source(resultStream, dbName, collName);
        final var comparator = keyComparator(ascending);
        if (bound > 0 && bound <= MAX_BOUNDED_SORT) {
            return new Sorted(boundedSort(source, fieldName, comparator, (int) bound), false);
        }
        return new Sorted(fullSort(source, fieldName, comparator, new HeldRows(context)), false);
    }

    private static Sorted fetchSkipping(List<String> orderedIds, String dbName, String collName, long bound, long skip,
            PipelineContext context) {
        if (skip <= 0 || !locks.holdsCollectionLock(dbName, collName)) {
            return new Sorted(fetchInOrder(orderedIds, dbName, collName, bound, context), false);
        }
        context.tick(Math.min(skip, orderedIds.size()));
        final var rest = orderedIds.subList((int) Math.min(skip, orderedIds.size()), orderedIds.size());
        return new Sorted(fetchInOrder(rest, dbName, collName, bound > 0 ? bound - skip : bound, context), true);
    }

    private static Comparator<Keyed> keyComparator(boolean ascending) {
        final Comparator<Keyed> byKey = ascending
                ? (a, b) -> JsonUtils.compareSortKeysAscending(a.key(), b.key())
                : (a, b) -> JsonUtils.compareSortKeysDescending(a.key(), b.key());
        return byKey.thenComparing(SortOperatorHelper::idOf).thenComparingInt(Keyed::seq);
    }

    private static String idOf(Keyed keyed) {
        final var id = keyed.document().get(Globals.PK_FIELD);
        return id instanceof JsonString jsonString ? jsonString.getValue() : "";
    }

    private static Stream<JsonObject> fullSort(Stream<JsonObject> source, String fieldName,
            Comparator<Keyed> comparator, HeldRows held) {
        final var decorated = decorate(source, fieldName, held);
        Arrays.parallelSort(decorated, comparator);
        return Arrays.stream(decorated).map(Keyed::document);
    }

    private static Stream<JsonObject> boundedSort(Stream<JsonObject> source, String fieldName,
            Comparator<Keyed> comparator, int bound) {
        final var heap = new PriorityQueue<>(comparator.reversed());
        final var seq = new int[]{0};
        final var path = FieldPath.of(fieldName);
        try (var documents = source) {
            documents.forEach(document -> {
                final var keyed = new Keyed(document, path.valueIn(document), seq[0]++);
                if (heap.size() < bound) {
                    heap.offer(keyed);
                } else if (comparator.compare(heap.peek(), keyed) > 0) {
                    heap.poll();
                    heap.offer(keyed);
                }
            });
        }
        final var retained = heap.toArray(Keyed[]::new);
        Arrays.sort(retained, comparator);
        return Arrays.stream(retained).map(Keyed::document);
    }

    private static Keyed[] decorate(Stream<JsonObject> source, String fieldName, HeldRows held) {
        final var decorated = new ArrayList<Keyed>();
        final var path = FieldPath.of(fieldName);
        try (var documents = source) {
            documents.forEach(document -> {
                held.hold(document);
                decorated.add(new Keyed(document, path.valueIn(document), decorated.size()));
            });
        }
        return decorated.toArray(Keyed[]::new);
    }

    static IndexedSource.Fast tryIndexedRangeTopK(List<BaseAggregationStep> steps, String dbName, String collName,
            long bound, PipelineContext context) throws IOException {
        if (bound <= 0 || steps.size() < 2 || !(steps.get(1) instanceof SortAggregationStep sortStep)) {
            return null;
        }
        final var range = SingleRange.of(steps.getFirst());
        if (range == null || !range.bounds().fieldName().equals(sortStep.getFieldName())) {
            return IndexOrderTopK.tryWalk(steps, sortStep, dbName, collName, bound, context);
        }
        final var ids = RangeIdWalk.open(dbName, collName, range.bounds(), sortStep.getAscending(), bound, context);
        if (ids == null) {
            return null;
        }
        final var skip = AggregationOperationHelper.skipAfter(steps, 1);
        if (skip <= 0) {
            return new IndexedSource.Fast(
                    context.watch(fetchInOrder(ids, dbName, collName, bound, context).filter(range.matches())), 2);
        }
        context.tick(skipIds(ids, skip));
        return new IndexedSource.Fast(
                context.watch(fetchInOrder(ids, dbName, collName, bound - skip, context).filter(range.matches())), 3);
    }

    private static long skipIds(Iterator<String> ids, long skip) {
        var skipped = 0L;
        while (skipped < skip && ids.hasNext()) {
            ids.next();
            skipped++;
        }
        return skipped;
    }

    private static Stream<JsonObject> fetchInOrder(List<String> orderedIds, String dbName, String collName, long bound,
            PipelineContext context) {
        return fetchInOrder(orderedIds.iterator(), dbName, collName, bound, context);
    }

    static Stream<JsonObject> fetchInOrder(Iterator<String> orderedIds, String dbName, String collName, long bound,
            PipelineContext context) {
        final var chunks = Spliterators.spliteratorUnknownSize(chunkIterator(orderedIds, firstChunkSize(bound)),
                Spliterator.ORDERED);
        return StreamSupport.stream(chunks, false).flatMap(chunk -> fetchChunk(chunk, dbName, collName, context));
    }

    private static Stream<JsonObject> sortViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, boolean ascending, long bound, PipelineContext context) {
        final var order = entryOrder(ascending);
        final var sortedEntries = new ArrayList<>(indexEntries);
        sortedEntries.sort(order);
        return fetchInOrder(IndexHelper.idsInOrder(sortedEntries, order, bound > 0 ? bound : Long.MAX_VALUE), dbName,
                collName, bound, context);
    }

    static Comparator<FieldIndexEntry<?>> entryOrder(boolean ascending) {
        return (a, b) -> compareIndexValues(a.getValue(), b.getValue(), ascending);
    }

    private static int firstChunkSize(long bound) {
        if (bound <= 0) {
            return MIN_ID_FETCH_CHUNK;
        }
        return (int) Math.min(bound, MAX_ID_FETCH_CHUNK);
    }

    private static Iterator<List<String>> chunkIterator(Iterator<String> ids, int firstChunkSize) {
        return new Iterator<>() {
            private int chunkSize = firstChunkSize;

            @Override
            public boolean hasNext() {
                return ids.hasNext();
            }

            @Override
            public List<String> next() {
                if (!ids.hasNext()) {
                    throw new NoSuchElementException();
                }
                final var chunk = new ArrayList<String>(chunkSize);
                while (chunk.size() < chunkSize && ids.hasNext()) {
                    chunk.add(ids.next());
                }
                chunkSize = Math.min(chunkSize * 2, MAX_ID_FETCH_CHUNK);
                return chunk;
            }
        };
    }

    private static Stream<JsonObject> fetchChunk(List<String> chunk, String dbName, String collName,
            PipelineContext context) {
        try {
            final var documents = cache.getEntriesByIds(dbName, collName, Set.copyOf(chunk), context::pinnedPkIndex);
            context.scanned(documents.size());
            if (documents.size() == 1) {
                return Stream.of(documents.getFirst().getData());
            }
            final var byId = new HashMap<String, JsonObject>(documents.size() * 2);
            for (final var dbEntry : documents) {
                byId.put(dbEntry.get_id(), dbEntry.getData());
            }
            final var ordered = new ArrayList<JsonObject>(documents.size());
            for (final var id : chunk) {
                final var document = byId.get(id);
                if (document != null) {
                    ordered.add(document);
                }
            }
            return ordered.stream();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int compareIndexValues(Object a, Object b, boolean ascending) {
        final var elemA = toSortKey(a);
        final var elemB = toSortKey(b);
        return ascending
                ? JsonUtils.compareSortKeysAscending(elemA, elemB)
                : JsonUtils.compareSortKeysDescending(elemA, elemB);
    }

    private static JsonBaseElement toSortKey(Object value) {
        return value == null || value instanceof JsonNull ? JsonNull.INSTANCE : IndexHelper.indexValueToElement(value);
    }
}
