package org.techhouse.ops;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

final class LazyCandidates {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    private LazyCandidates() {
    }

    static Stream<JsonObject> fetched(String dbName, String collName, Set<String> ids, long bound,
            PipelineContext context) throws IOException {
        if (bound <= 0 || ids.size() <= Globals.LAZY_CANDIDATE_CHUNKS.getFirst()
                || !locks.holdsCollectionLock(dbName, collName)) {
            final var entries = cache.getEntriesByIds(dbName, collName, ids);
            context.scanned(entries.size());
            return entries.stream().map(DbEntry::getData);
        }
        final var chunks = Spliterators.spliteratorUnknownSize(new Chunks(ids.iterator()), Spliterator.ORDERED);
        return StreamSupport.stream(chunks, false).flatMap(chunk -> fetchChunk(chunk, dbName, collName, context));
    }

    private static Stream<JsonObject> fetchChunk(Set<String> chunk, String dbName, String collName,
            PipelineContext context) {
        try {
            final var entries = cache.getEntriesByIds(dbName, collName, chunk, context::pinnedPkIndex);
            context.scanned(entries.size());
            return entries.stream().map(DbEntry::getData);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Chunks implements Iterator<Set<String>> {
        private final Iterator<String> ids;
        private int step;

        private Chunks(Iterator<String> ids) {
            this.ids = ids;
        }

        @Override
        public boolean hasNext() {
            return ids.hasNext();
        }

        @Override
        public Set<String> next() {
            if (!ids.hasNext()) {
                throw new NoSuchElementException();
            }
            final var size = Globals.LAZY_CANDIDATE_CHUNKS.get(step);
            step = Math.min(step + 1, Globals.LAZY_CANDIDATE_CHUNKS.size() - 1);
            final var chunk = HashSet.<String>newHashSet(size);
            while (chunk.size() < size && ids.hasNext()) {
                chunk.add(ids.next());
            }
            return chunk;
        }
    }
}
