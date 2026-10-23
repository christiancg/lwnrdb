package org.techhouse.cache;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.FileSystem;

public final class WholeLoads {
    public interface Admission {
        boolean admits(long estimatedBytes);
    }

    private interface Load<T> {
        T run() throws IOException;
    }

    private static final Object LEADER_INTERRUPTED = new Object();

    private final Supplier<FileSystem> fileSystem;
    private final Semaphore transientPermits;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public WholeLoads(Supplier<FileSystem> fileSystem, int transientLoadPermits) {
        this.fileSystem = fileSystem;
        this.transientPermits = new Semaphore(Math.max(1, transientLoadPermits), true);
    }

    public List<PkIndexEntry> pkIndex(String dbName, String collName, boolean lockHeld, Admission admission)
            throws IOException {
        final Load<List<PkIndexEntry>> load = () -> {
            final var estimate = CacheSizeEstimator
                    .estimatePkIndexSizeFromFileBytes(fs().pkIndexFileBytes(dbName, collName));
            return run(admission.admits(estimate), () -> fs().readWholePkIndexFile(dbName, collName));
        };
        return lockHeld ? once("pk|" + Cache.getCollectionIdentifier(dbName, collName), load) : load.run();
    }

    public List<PkIndexEntry> transientPkIndex(String dbName, String collName) throws IOException {
        return run(false, () -> fs().readWholePkIndexFile(dbName, collName));
    }

    public <T> List<FieldIndexEntry<T>> fieldIndex(String dbName, String collName, String fieldName, Class<T> indexType,
            Admission admission) throws IOException {
        final var key = "field|" + Cache.getCollectionIdentifier(dbName, collName) + "|"
                + Cache.getIndexIdentifier(fieldName, indexType);
        return once(key, () -> {
            final var bytes = fs().indexFileBytes(dbName, collName, fieldName, IndexKind.fileLabel(indexType));
            return run(admission.admits(CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes)),
                    () -> fs().readWholeFieldIndexFiles(dbName, collName, fieldName, indexType));
        });
    }

    public List<FieldIndexEntry<String>> hashIndex(String dbName, String collName, String fieldName, IndexKind kind,
            Admission admission) throws IOException {
        final var key = "hash|" + Cache.getCollectionIdentifier(dbName, collName) + "|"
                + Cache.getHashIndexIdentifier(fieldName, kind.label());
        return once(key, () -> {
            final var bytes = fs().indexFileBytes(dbName, collName, fieldName, kind.label());
            return run(admission.admits(CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes)),
                    () -> fs().readWholeHashIndexFile(dbName, collName, fieldName, kind));
        });
    }

    private FileSystem fs() {
        return fileSystem.get();
    }

    public int availablePermits() {
        return transientPermits.availablePermits();
    }

    public int loadsInFlight() {
        return inFlight.size();
    }

    private <T> T once(String key, Load<T> load) throws IOException {
        while (true) {
            final var mine = new CompletableFuture<>();
            final var running = inFlight.putIfAbsent(key, mine);
            if (running == null) {
                return lead(key, mine, load);
            }
            final var shared = awaitShared(running);
            if (shared != LEADER_INTERRUPTED) {
                @SuppressWarnings("unchecked")
                final var typed = (T) shared;
                return typed;
            }
        }
    }

    private <T> T lead(String key, CompletableFuture<Object> mine, Load<T> load) throws IOException {
        try {
            final var loaded = load.run();
            mine.complete(loaded);
            return loaded;
        } catch (IOException | RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
            if (!mine.isDone()) {
                mine.completeExceptionally(new IOException("The shared load " + key + " ended without a result"));
            }
        }
    }

    private static Object awaitShared(CompletableFuture<Object> running) throws IOException {
        try {
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for another caller's load of the same index");
        } catch (ExecutionException e) {
            return rethrow(e.getCause());
        }
    }

    private static Object rethrow(Throwable cause) throws IOException {
        return switch (cause) {
            case InterruptedIOException _, ClosedByInterruptException _ -> LEADER_INTERRUPTED;
            case IOException io -> throw io;
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            case null, default -> throw new IOException(cause);
        };
    }

    private <T> T run(boolean admissible, Load<T> load) throws IOException {
        if (admissible) {
            return load.run();
        }
        try {
            transientPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for a transient load permit");
        }
        try {
            return load.run();
        } finally {
            transientPermits.release();
        }
    }
}
