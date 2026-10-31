package org.techhouse.ops.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IdSet;
import org.techhouse.data.IndexKind;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.utils.FieldPath;

public final class IndexBuild {
    public static final long BUILDER_BYTES_PER_DOCUMENT_PER_FIELD = 170L;
    public static final long MIN_PASS_BUDGET_BYTES = 64L * 1024 * 1024;
    public static final long UNBOUNDED_PASS_BUDGET_BYTES = 256L * 1024 * 1024;
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ResourceLocking rl = IocContainer.get(ResourceLocking.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    private static final Configuration configuration = Configuration.getInstance();
    @SuppressWarnings("FieldMayBeFinal")
    private static FileSystem fs = IocContainer.get(FileSystem.class);
    @SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
    private static long bytesPerDocumentPerField = BUILDER_BYTES_PER_DOCUMENT_PER_FIELD;

    private IndexBuild() {
    }

    public static void buildAll(String dbName, String collName, List<String> fields)
            throws IOException, InterruptedException {
        if (fields.isEmpty()) {
            return;
        }
        var finished = false;
        try {
            for (final var pass : passesOf(fields, documentCount(dbName, collName))) {
                buildPass(dbName, collName, pass);
            }
            finished = true;
        } finally {
            if (!finished) {
                dropEvery(dbName, collName, fields);
            }
        }
    }

    public static List<List<String>> passesOf(List<String> fields, long documents) {
        final var perField = Math.max(1L, documents) * bytesPerDocumentPerField;
        final var perPass = (int) Math.clamp(passBudgetBytes() / perField, 1L, fields.size());
        final var passes = new ArrayList<List<String>>();
        for (var from = 0; from < fields.size(); from += perPass) {
            passes.add(List.copyOf(fields.subList(from, Math.min(fields.size(), from + perPass))));
        }
        return passes;
    }

    public static long passBudgetBytes() {
        if (configuration.isCachingDisabled() || configuration.isCacheUnlimited()) {
            return UNBOUNDED_PASS_BUDGET_BYTES;
        }
        return Math.max(configuration.getMaxMemoryBytes() / 2, MIN_PASS_BUDGET_BYTES);
    }

    private static long documentCount(String dbName, String collName) {
        final var pages = cache.getAdminPageEntries(dbName, collName);
        var documents = 0L;
        if (pages != null) {
            for (final var page : pages) {
                documents += page.getEntryCount();
            }
        }
        return documents;
    }

    private static void buildPass(String dbName, String collName, List<String> fields)
            throws IOException, InterruptedException {
        final var builders = new ArrayList<FieldBuilder>(fields.size());
        for (final var field : fields) {
            builders.add(new FieldBuilder(dbName, collName, field));
        }
        forEachDocument(dbName, collName, document -> {
            for (final var builder : builders) {
                builder.add(document);
            }
        });
        for (final var builder : builders) {
            builder.write();
            faultInjector.at(FaultPoint.INDEX_BUILD_FIELD_WRITTEN, Cache.getCollectionIdentifier(dbName, collName));
        }
    }

    private interface DocumentSink {
        void accept(DbEntry document);
    }

    private static void forEachDocument(String dbName, String collName, DocumentSink sink) throws IOException {
        final var cached = cache.userCache().getCachedCollection(dbName, collName);
        if (cached != null && !cached.isEmpty() && cached.size() == cache.pkIndexSize(dbName, collName)) {
            for (final var document : cached.values()) {
                sink.accept(document);
            }
            return;
        }
        try (var pages = fs.streamPages(dbName, collName)) {
            pages.forEach(page -> {
                for (final var document : page.values()) {
                    sink.accept(document);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void dropEvery(String dbName, String collName, List<String> fields) {
        for (final var field : fields) {
            var locked = false;
            try {
                rl.lockIndex(dbName, collName, field);
                locked = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                fs.dropIndex(dbName, collName, field);
                cache.evictFieldIndexAllTypes(dbName, collName, field);
            } finally {
                if (locked) {
                    rl.releaseIndex(dbName, collName, field);
                }
            }
        }
    }

    private static final class FieldBuilder {
        private final String dbName;
        private final String collName;
        private final String field;
        private final FieldPath path;
        private final Map<Class<?>, Map<String, FieldIndexEntry<Object>>> scalars = new LinkedHashMap<>();
        private final Map<IndexKind, Map<String, FieldIndexEntry<Object>>> hashes = new EnumMap<>(IndexKind.class);

        private FieldBuilder(String dbName, String collName, String field) {
            this.dbName = dbName;
            this.collName = collName;
            this.field = field;
            this.path = FieldPath.of(field);
        }

        private void add(DbEntry document) {
            final var indexed = IndexedValue.of(path.valueIn(document.getData()));
            if (indexed == null) {
                return;
            }
            final var byKey = indexed.isHashed()
                    ? hashes.computeIfAbsent(indexed.kind(), _ -> new LinkedHashMap<>())
                    : scalars.computeIfAbsent(indexed.type(), _ -> new LinkedHashMap<>());
            var entry = byKey.get(indexed.key());
            if (entry == null) {
                entry = new FieldIndexEntry<>(dbName, collName, indexed.value(), new IdSet());
                byKey.put(indexed.key(), entry);
            }
            entry.getIds().add(document.get_id());
        }

        private void write() throws InterruptedException {
            final var scalarEntries = new LinkedHashMap<Class<?>, List<FieldIndexEntry<?>>>();
            for (final var type : scalars.entrySet()) {
                scalarEntries.put(type.getKey(), new ArrayList<>(type.getValue().values()));
            }
            rl.lockIndex(dbName, collName, field);
            try {
                fs.dropIndex(dbName, collName, field);
                fs.writeIndexFile(dbName, collName, field, scalarEntries);
                for (final var kind : hashes.entrySet()) {
                    fs.writeHashIndexFile(dbName, collName, field, kind.getKey(), cast(kind.getValue().values()));
                }
                cache.evictFieldIndexAllTypes(dbName, collName, field);
            } finally {
                rl.releaseIndex(dbName, collName, field);
            }
            scalars.clear();
            hashes.clear();
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static List<FieldIndexEntry<String>> cast(Collection<FieldIndexEntry<Object>> entries) {
            return new ArrayList(entries);
        }
    }
}
