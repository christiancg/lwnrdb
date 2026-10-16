package org.techhouse.cache;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
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

    private final Supplier<FileSystem> fileSystem;
    private final Semaphore transientPermits;

    public WholeLoads(Supplier<FileSystem> fileSystem, int transientLoadPermits) {
        this.fileSystem = fileSystem;
        this.transientPermits = new Semaphore(Math.max(1, transientLoadPermits), true);
    }

    public List<PkIndexEntry> pkIndex(String dbName, String collName, Admission admission) throws IOException {
        final var estimate = CacheSizeEstimator
                .estimatePkIndexSizeFromFileBytes(fs().pkIndexFileBytes(dbName, collName));
        return run(admission.admits(estimate), () -> fs().readWholePkIndexFile(dbName, collName));
    }

    public List<PkIndexEntry> transientPkIndex(String dbName, String collName) throws IOException {
        return run(false, () -> fs().readWholePkIndexFile(dbName, collName));
    }

    public <T> List<FieldIndexEntry<T>> fieldIndex(String dbName, String collName, String fieldName, Class<T> indexType,
            Admission admission) throws IOException {
        final var bytes = fs().indexFileBytes(dbName, collName, fieldName, IndexKind.fileLabel(indexType));
        return run(admission.admits(CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes)),
                () -> fs().readWholeFieldIndexFiles(dbName, collName, fieldName, indexType));
    }

    public List<FieldIndexEntry<String>> hashIndex(String dbName, String collName, String fieldName, IndexKind kind,
            Admission admission) throws IOException {
        final var bytes = fs().indexFileBytes(dbName, collName, fieldName, kind.label());
        return run(admission.admits(CacheSizeEstimator.estimateFieldIndexSizeFromFileBytes(bytes)),
                () -> fs().readWholeHashIndexFile(dbName, collName, fieldName, kind));
    }

    private FileSystem fs() {
        return fileSystem.get();
    }

    public int availablePermits() {
        return transientPermits.availablePermits();
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
