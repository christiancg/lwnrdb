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
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.utils.JsonUtils;

public final class SortOperatorHelper {
    private static final int MAX_ID_FETCH_CHUNK = 256;
    private static final int MIN_ID_FETCH_CHUNK = 32;
    private static final int MAX_BOUNDED_SORT = 4096;
    private static final Cache cache = IocContainer.get(Cache.class);

    private SortOperatorHelper() {
    }

    private record Keyed(JsonObject document, JsonBaseElement key, int seq) {
    }

    public static Stream<JsonObject> processSortStep(SortAggregationStep sortStep, Stream<JsonObject> resultStream,
            String dbName, String collName, long bound) throws IOException {
        final var fieldName = sortStep.getFieldName();
        final var ascending = sortStep.getAscending();
        if (resultStream == null) {
            final var orderedIds = IndexHelper.getSortedIdsForField(dbName, collName, fieldName,
                    (a, b) -> compareIndexValues(a.getValue(), b.getValue(), ascending),
                    bound > 0 ? bound : Long.MAX_VALUE);
            if (orderedIds != null) {
                return fetchInOrder(orderedIds, dbName, collName, bound);
            }
            final var indexEntries = IndexHelper.getIndexEntriesForField(dbName, collName, fieldName);
            if (indexEntries != null) {
                return sortViaIndex(indexEntries, dbName, collName, ascending, bound);
            }
        }
        final var source = cache.initializeStreamIfNecessary(resultStream, dbName, collName);
        final var comparator = keyComparator(ascending);
        if (bound > 0 && bound <= MAX_BOUNDED_SORT) {
            return boundedSort(source, fieldName, comparator, (int) bound);
        }
        return fullSort(source, fieldName, comparator);
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
            Comparator<Keyed> comparator) {
        final var decorated = decorate(source, fieldName);
        Arrays.parallelSort(decorated, comparator);
        return Arrays.stream(decorated).map(Keyed::document);
    }

    private static Stream<JsonObject> boundedSort(Stream<JsonObject> source, String fieldName,
            Comparator<Keyed> comparator, int bound) {
        final var heap = new PriorityQueue<>(comparator.reversed());
        final var seq = new int[]{0};
        source.forEach(document -> {
            final var keyed = new Keyed(document, JsonUtils.getFromPath(document, fieldName), seq[0]++);
            if (heap.size() < bound) {
                heap.offer(keyed);
            } else if (comparator.compare(heap.peek(), keyed) > 0) {
                heap.poll();
                heap.offer(keyed);
            }
        });
        final var retained = heap.toArray(Keyed[]::new);
        Arrays.sort(retained, comparator);
        return Arrays.stream(retained).map(Keyed::document);
    }

    private static Keyed[] decorate(Stream<JsonObject> source, String fieldName) {
        final var decorated = new ArrayList<Keyed>();
        source.forEach(document -> decorated
                .add(new Keyed(document, JsonUtils.getFromPath(document, fieldName), decorated.size())));
        return decorated.toArray(Keyed[]::new);
    }

    private static Stream<JsonObject> fetchInOrder(List<String> orderedIds, String dbName, String collName,
            long bound) {
        final var chunks = Spliterators.spliteratorUnknownSize(
                chunkIterator(orderedIds.iterator(), firstChunkSize(bound)), Spliterator.ORDERED);
        return StreamSupport.stream(chunks, false).flatMap(chunk -> fetchChunk(chunk, dbName, collName));
    }

    private static Stream<JsonObject> sortViaIndex(List<FieldIndexEntry<?>> indexEntries, String dbName,
            String collName, boolean ascending, long bound) {
        final var sortedEntries = new ArrayList<>(indexEntries);
        sortedEntries.sort((a, b) -> compareIndexValues(a.getValue(), b.getValue(), ascending));
        final var ids = sortedEntries.stream().flatMap(entry -> IndexHelper.sortedIds(entry).stream()).iterator();
        final var chunks = Spliterators.spliteratorUnknownSize(chunkIterator(ids, firstChunkSize(bound)),
                Spliterator.ORDERED);
        return StreamSupport.stream(chunks, false).flatMap(chunk -> fetchChunk(chunk, dbName, collName));
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

    private static Stream<JsonObject> fetchChunk(List<String> chunk, String dbName, String collName) {
        try {
            final var documents = cache.getEntriesByIds(dbName, collName, Set.copyOf(chunk));
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
