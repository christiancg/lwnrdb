package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.ChunkedChannelWrites;
import org.techhouse.log.Logger;

final class WalWriteGroup {
    private static final Logger logger = Logger.logFor(WalWriteGroup.class);
    private static final String TAKE_BACK_FAILURE = "Could not take back a failed group of writes from ";
    private static final byte[] ZEROS = new byte[Globals.WAL_PREALLOCATE_MIN_BYTES];

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

    long write(File log, long at, long allocated, byte[] frame, int length, boolean force) throws IOException {
        if (channel == null) {
            channel = openWritable(log);
        }
        try {
            return writeFrame(channel, log, at, allocated, frame, length, force);
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

    static FileChannel openWritable(File log) throws IOException {
        return FileChannel.open(log.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    static long writeFrame(FileChannel writable, File log, long at, long allocated, byte[] frame, int length,
            boolean force) throws IOException {
        final var end = at + length;
        final var room = end > allocated ? preallocate(writable, allocated, end) : allocated;
        ChunkedChannelWrites.writeAt(writable, at, frame, 0, length);
        if (force) {
            SyncedWrites.content(writable, log.toPath());
        }
        return room;
    }

    private static long preallocate(FileChannel writable, long allocated, long end) throws IOException {
        final var growth = Math.clamp(allocated, Globals.WAL_PREALLOCATE_MIN_BYTES, Globals.WAL_PREALLOCATE_MAX_BYTES);
        final var room = Math.max(end, allocated + growth);
        zero(writable, allocated, room);
        return room;
    }

    private static void zero(FileChannel writable, long from, long to) throws IOException {
        for (var at = from; at < to; at += ZEROS.length) {
            ChunkedChannelWrites.writeAt(writable, at, ZEROS, 0, (int) Math.min(ZEROS.length, to - at));
        }
    }

    static boolean erase(File log, long from, long to, String failureMessage) {
        if (to <= from || !log.isFile()) {
            return true;
        }
        try (var writable = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
            zero(writable, from, to);
            SyncedWrites.content(writable, log.toPath());
            return true;
        } catch (IOException e) {
            logger.error(failureMessage, e);
            return false;
        }
    }

    static long writtenEnd(byte[] bytes, long from) {
        for (var at = bytes.length - 1; at >= from; at--) {
            if (bytes[at] != 0) {
                return at + 1L;
            }
        }
        return from;
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
            PageLengths.forget(deferred.page());
            logger.error("Committed a group of writes to " + deferred.page().getName() + " but could not blank the"
                    + " copies they superseded; they are blanked before the next write to the collection, or at the"
                    + " next startup", e);
            return false;
        }
        return true;
    }

    boolean takeBack(File log, long logEnd, File pkIndex) {
        var cutBack = true;
        for (final var entry : lengthsBefore.entrySet()) {
            final var file = entry.getKey();
            PageLengths.forget(file);
            if (file.equals(pkIndex)) {
                PkIndexStore.forgetState(file);
            }
            cutBack &= FileLocks.cutBackTo(file, entry.getValue(), TAKE_BACK_FAILURE + file.getName());
        }
        blanks.clear();
        return cutBack && erase(log, logLengthBefore, logEnd, TAKE_BACK_FAILURE + log.getName());
    }

    void restartFrom(long logLength) {
        dropChannel();
        rebase(logLength);
    }

    void rebase(long logLength) {
        logLengthBefore = logLength;
        lengthsBefore.clear();
        blanks.clear();
        records = 0;
        forced = false;
    }
}
