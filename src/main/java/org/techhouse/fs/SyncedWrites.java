package org.techhouse.fs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class SyncedWrites {
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(SyncedWrites.class);
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static volatile Forcer forcer = Forcer.DEVICE;

    private SyncedWrites() {
    }

    static void useForcer(Forcer replacement) {
        forcer = replacement;
    }

    static void forceOrFail(FileChannel channel, Path path) throws IOException {
        if (faultInjector.isEnabled() && faultInjector.failsAt(FaultPoint.FILE_FORCED, subjectOf(path))) {
            forcer.forceFailed(path);
            ForceFailures.failed(path);
            ForceMetrics.failed();
            throw new IOException("injected force failure of " + path);
        }
        final var startedAt = System.nanoTime();
        try {
            forcer.force(channel, path);
        } catch (IOException | RuntimeException e) {
            ForceFailures.failed(path);
            ForceMetrics.failed();
            throw e;
        }
        ForceMetrics.observe(System.nanoTime() - startedAt);
    }

    private static String subjectOf(Path path) {
        final var name = String.valueOf(path.getFileName());
        return Files.isDirectory(path) ? name + "/" : name;
    }

    static boolean enabled() {
        return configuration.isSyncWrites();
    }

    static void content(FileChannel channel, Path file) throws IOException {
        if (enabled()) {
            forceOrFail(channel, file);
        }
    }

    static void content(Path file) throws IOException {
        if (enabled()) {
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                forceOrFail(channel, file);
            }
        }
    }

    static void contentTogether(List<Path> files) throws IOException {
        if (!enabled() || files.isEmpty()) {
            return;
        }
        final var failures = new ConcurrentLinkedQueue<IOException>();
        final var others = new ArrayList<Thread>(files.size() - 1);
        for (final var file : files.subList(1, files.size())) {
            others.add(Thread.ofVirtual().start(() -> forceCollecting(file, failures)));
        }
        forceCollecting(files.getFirst(), failures);
        for (final var other : others) {
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while syncing " + files);
            }
        }
        final var first = failures.poll();
        if (first != null) {
            failures.forEach(first::addSuppressed);
            throw first;
        }
    }

    private static void forceCollecting(Path file, Queue<IOException> failures) {
        try {
            content(file);
        } catch (NoSuchFileException e) {
            if (!isFieldIndexLog(file)) {
                failures.add(e);
                return;
            }
            logger.info("Skipped forcing " + file + ": it was removed before its force, so none of its bytes are left"
                    + " to make durable");
        } catch (IOException e) {
            failures.add(e);
        }
    }

    private static boolean isFieldIndexLog(Path file) {
        final var name = file.getFileName();
        return name != null && name.toString().endsWith(Globals.INDEX_LOG_FILE_EXTENSION);
    }

    static void entries(Path folder) throws IOException {
        if (enabled()) {
            forceFolder(folder);
        }
    }

    static void entriesOfLanded(Path folder, String change) {
        try {
            entries(folder);
        } catch (IOException e) {
            logger.error(change + " landed, but its folder " + folder + " could not be synced to the device; a power"
                    + " loss before the folder's next sync may undo it", e);
        }
    }

    static void verifyFolderSync(Path folder) throws IOException {
        if (enabled()) {
            forceFolder(folder);
        }
    }

    private static void forceFolder(Path folder) throws IOException {
        try (var channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            forceOrFail(channel, folder);
        }
    }
}
