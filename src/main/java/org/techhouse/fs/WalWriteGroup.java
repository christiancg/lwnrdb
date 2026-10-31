package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.log.Logger;

final class WalWriteGroup {
    private static final Logger logger = Logger.logFor(WalWriteGroup.class);
    private static final String TAKE_BACK_FAILURE = "Could not take back a failed group of writes from ";

    private final Map<File, Long> lengthsBefore = new LinkedHashMap<>();
    private final List<DeferredBlanks> blanks = new ArrayList<>();
    private long logLengthBefore;
    private int records;
    private boolean failed;
    private boolean forced;
    private FileChannel channel;

    private record DeferredBlanks(File page, List<long[]> regions) {
    }

    WalWriteGroup(long logLength) {
        this.logLengthBefore = logLength;
    }

    void recorded() {
        records++;
    }

    int records() {
        return records;
    }

    long logLengthBefore() {
        return logLengthBefore;
    }

    boolean failed() {
        return failed;
    }

    void fail() {
        failed = true;
    }

    boolean forced() {
        return forced;
    }

    void markForced() {
        forced = true;
    }

    void dropChannel() {
        final var held = channel;
        channel = null;
        if (held == null) {
            return;
        }
        try {
            held.close();
        } catch (IOException e) {
            logger.warning("Could not close the write-ahead log channel of a write group: " + e.getMessage());
        }
    }

    void append(File log, byte[] header, byte[] payload, int payloadLength, boolean force) throws IOException {
        if (channel == null) {
            channel = openAppending(log);
        }
        try {
            appendTo(channel, log, header, payload, payloadLength, force);
        } catch (IOException | RuntimeException e) {
            dropChannel();
            throw e;
        }
    }

    void forceLog(File log) throws IOException {
        if (channel == null) {
            forceUnheld(log);
            return;
        }
        try {
            SyncedWrites.content(channel, log.toPath());
        } catch (IOException | RuntimeException e) {
            dropChannel();
            throw e;
        }
    }

    static FileChannel openAppending(File log) throws IOException {
        return FileChannel.open(log.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void appendTo(FileChannel appending, File log, byte[] header, byte[] payload, int payloadLength,
            boolean force) throws IOException {
        final var buffers = new ByteBuffer[]{ByteBuffer.wrap(header), ByteBuffer.wrap(payload, 0, payloadLength)};
        var unwritten = (long) header.length + payloadLength;
        while (unwritten > 0) {
            unwritten -= appending.write(buffers);
        }
        if (force) {
            SyncedWrites.content(appending, log.toPath());
        }
    }

    static void forceUnheld(File log) throws IOException {
        try (var writable = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
            SyncedWrites.content(writable, log.toPath());
        }
    }

    boolean idle() {
        return records == 0 && blanks.isEmpty();
    }

    void rememberLength(File file, long length) {
        lengthsBefore.putIfAbsent(file, length);
    }

    void deferBlanks(File page, List<long[]> regions) {
        blanks.add(new DeferredBlanks(page, List.copyOf(regions)));
    }

    boolean applyBlanks() {
        try {
            var blanked = true;
            for (final var deferred : blanks) {
                blanked &= blank(deferred);
            }
            return blanked;
        } finally {
            blanks.clear();
        }
    }

    private static boolean blank(DeferredBlanks deferred) {
        try (var channel = FileChannel.open(deferred.page().toPath(), StandardOpenOption.WRITE)) {
            for (final var region : deferred.regions()) {
                PageHoles.blank(channel, region[0], region[1]);
            }
        } catch (IOException e) {
            PageTails.forget(deferred.page());
            logger.error("Committed a group of writes to " + deferred.page().getName() + " but could not blank the"
                    + " copies they superseded; they are blanked before the next write to the collection, or at the"
                    + " next startup", e);
            return false;
        }
        PageTails.blanked(deferred.page());
        return true;
    }

    boolean takeBack(File log, File pkIndex) {
        var cutBack = true;
        for (final var entry : lengthsBefore.entrySet()) {
            final var file = entry.getKey();
            PageTails.forget(file);
            if (file.equals(pkIndex)) {
                PkIndexStore.forgetState(file);
            }
            cutBack &= !file.exists() || file.length() == entry.getValue()
                    || FileLocks.truncateTo(file, entry.getValue(), TAKE_BACK_FAILURE + file.getName());
        }
        blanks.clear();
        return cutBack && (log.length() == logLengthBefore
                || FileLocks.truncateTo(log, logLengthBefore, TAKE_BACK_FAILURE + log.getName()));
    }

    void restartFrom(long logLength) {
        dropChannel();
        logLengthBefore = logLength;
        lengthsBefore.clear();
        blanks.clear();
        records = 0;
        forced = false;
    }
}
