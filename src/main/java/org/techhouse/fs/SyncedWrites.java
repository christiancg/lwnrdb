package org.techhouse.fs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

final class SyncedWrites {
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(SyncedWrites.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static volatile Forcer forcer = Forcer.DEVICE;

    private SyncedWrites() {
    }

    static boolean enabled() {
        return configuration.isSyncWrites();
    }

    static void content(FileChannel channel, Path file) throws IOException {
        if (enabled()) {
            forcer.force(channel, file);
        }
    }

    static void content(Path file) throws IOException {
        if (enabled()) {
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                forcer.force(channel, file);
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
        } catch (IOException e) {
            failures.add(e);
        }
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
            forcer.force(channel, folder);
        }
    }
}
