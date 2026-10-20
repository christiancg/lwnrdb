package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class WalPlan {
    private static final Logger logger = Logger.logFor(WalPlan.class);
    private static final int PK_ROW_BYTES_HINT = 64;
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;
    private final WriteAheadLog wal;
    private final String dbName;
    private final String collName;
    private final TreeMap<Long, PageChanges> pages = new TreeMap<>();
    private final List<PkRow> pkRows = new ArrayList<>();

    private static final class PageChanges {
        private final File file;
        private final List<byte[]> lines = new ArrayList<>();
        private final List<long[]> blanks = new ArrayList<>();
        private long lengthBefore = -1L;
        private byte[] separator;
        private long next;
        private int payloadStart;
        private int payloadLength;

        private PageChanges(File file) {
            this.file = file;
        }

        private boolean appends() {
            return !lines.isEmpty();
        }
    }

    private record PkRow(PkIndexEntry row, String deletedId, int liveDelta) {
    }

    private record PkSlice(int start, int length) {
    }

    WalPlan(FilePaths paths, PkIndexStore pkIndexStore, String dbName, String collName) throws IOException {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
        this.wal = new WriteAheadLog(paths);
        this.dbName = dbName;
        this.collName = collName;
        wal.requireApplied(dbName, collName);
    }

    long append(long page, byte[] line) throws IOException {
        final var changes = changesOf(page);
        if (changes.lengthBefore < 0) {
            changes.lengthBefore = changes.file.exists() ? changes.file.length() : 0L;
            changes.separator = PageTails.separatorBefore(changes.file);
            changes.next = changes.lengthBefore + changes.separator.length;
        }
        final var offset = changes.next;
        changes.lines.add(line);
        changes.next += line.length;
        return offset;
    }

    void blank(long page, long offset, long length) {
        changesOf(page).blanks.add(new long[]{offset, length});
    }

    void insertRow(PkIndexEntry row) {
        pkRows.add(new PkRow(row, null, 1));
    }

    void replaceRow(PkIndexEntry row) {
        pkRows.add(new PkRow(row, null, 0));
    }

    void deleteRow(String id) {
        pkRows.add(new PkRow(null, id, -1));
    }

    void commit() throws IOException {
        commit(null, null);
    }

    void commit(FaultPoint afterLog, FaultPoint afterApply) throws IOException {
        if (pages.isEmpty() && pkRows.isEmpty()) {
            return;
        }
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        try {
            final var pk = pkRows.isEmpty() ? null : pkIndexStore.prepareAppend(dbName, collName);
            final var record = new WalCodec.RecordBuffer(expectedPayload(pk));
            encodePages(record);
            final var pkSlice = pk == null ? null : encodePkRows(record, pk);
            final var walLock = wal.lock(dbName, collName);
            try {
                final var seq = wal.append(dbName, collName, record, pages.keySet());
                fire(afterLog);
                final var group = wal.openGroupOf(dbName, collName);
                if (group != null) {
                    rememberLengths(group, pk);
                }
                applyOrAbort(seq, record, pk, pkSlice, group);
                fire(afterApply);
            } finally {
                walLock.unlock();
            }
            if (pk != null) {
                pkIndexStore.appended(dbName, collName, pk, pkRows.size(), liveDelta());
            }
            wal.checkpointIfFull(dbName, collName);
        } finally {
            pkLock.unlock();
        }
    }

    private long liveDelta() {
        var delta = 0L;
        for (final var row : pkRows) {
            delta += row.liveDelta();
        }
        return delta;
    }

    private PageChanges changesOf(long page) {
        return pages.computeIfAbsent(page, number -> new PageChanges(paths.collectionPage(dbName, collName, number)));
    }

    private void fire(FaultPoint point) {
        if (point != null) {
            faultInjector.at(point, WriteAheadLog.subject(dbName, collName));
        }
    }

    private void encodePages(WalCodec.RecordBuffer record) {
        for (final var page : pages.entrySet()) {
            final var changes = page.getValue();
            if (changes.appends()) {
                record.change(WalTarget.PAGE_WRITE, page.getKey(), changes.lengthBefore,
                        changes.next - changes.lengthBefore);
                changes.payloadStart = record.size();
                record.write(changes.separator, 0, changes.separator.length);
                for (final var line : changes.lines) {
                    record.write(line, 0, line.length);
                }
                changes.payloadLength = record.size() - changes.payloadStart;
            }
            for (final var blank : changes.blanks) {
                record.change(WalTarget.PAGE_BLANK, page.getKey(), blank[0], blank[1]);
            }
        }
    }

    private long expectedPayload(PkIndexStore.PkAppend pk) {
        var bytes = 0L;
        for (final var changes : pages.values()) {
            if (changes.appends()) {
                bytes += WalCodec.RecordBuffer.changeBytes(changes.next - changes.lengthBefore);
            }
            bytes += WalCodec.RecordBuffer.changeBytes(0L) * changes.blanks.size();
        }
        return pk == null ? bytes : bytes + WalCodec.RecordBuffer.changeBytes((long) pkRows.size() * PK_ROW_BYTES_HINT);
    }

    private PkSlice encodePkRows(WalCodec.RecordBuffer record, PkIndexStore.PkAppend pk) throws IOException {
        final var headerAt = record.change(WalTarget.PK_WRITE, -1L, pk.offset(), 0L);
        final var start = record.size();
        final var lines = new PkLineWriter(record);
        for (final var row : pkRows) {
            if (row.row() == null) {
                lines.writeDelete(row.deletedId());
            } else {
                lines.write(row.row());
            }
        }
        final var length = record.size() - start;
        record.patchLength(headerAt, length);
        return new PkSlice(start, length);
    }

    private void rememberLengths(WalWriteGroup group, PkIndexStore.PkAppend pk) {
        for (final var changes : pages.values()) {
            if (changes.appends()) {
                group.rememberLength(changes.file, changes.lengthBefore);
            }
        }
        if (pk != null) {
            group.rememberLength(pk.file(), pk.offset());
        }
    }

    private void deferBlanks(WalWriteGroup group) {
        for (final var changes : pages.values()) {
            if (!changes.blanks.isEmpty()) {
                group.deferBlanks(changes.file, changes.blanks);
            }
        }
    }

    private void applyOrAbort(long seq, WalCodec.RecordBuffer record, PkIndexStore.PkAppend pk, PkSlice pkSlice,
            WalWriteGroup group) throws IOException {
        try {
            for (final var changes : pages.values()) {
                if (changes.appends()) {
                    OwnerOnly.ensureOwnerOnlyFile(changes.file.toPath());
                    writeAt(changes.file, changes.lengthBefore, record.bytes(), changes.payloadStart,
                            changes.payloadLength);
                    PageTails.appended(changes.file, changes.lines.getLast());
                }
            }
            if (pk != null) {
                writeAt(pk.file(), pk.offset(), record.bytes(), pkSlice.start(), pkSlice.length());
            }
        } catch (IOException e) {
            abort(seq, pk, e);
            throw e;
        }
        if (group != null) {
            deferBlanks(group);
        } else {
            applyBlanks();
        }
    }

    private void abort(long seq, PkIndexStore.PkAppend pk, IOException failure) {
        var cutBack = true;
        for (final var changes : pages.values()) {
            if (changes.appends()) {
                cutBack &= cutBack(changes.file, changes.lengthBefore);
            }
        }
        if (pk != null) {
            PkIndexStore.forgetState(pk.file());
            cutBack &= cutBack(pk.file(), pk.offset());
        }
        if (cutBack) {
            try {
                wal.abort(dbName, collName, seq);
                return;
            } catch (IOException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
        wal.markUnapplied(dbName, collName);
        logger.error("Logged a write to " + WriteAheadLog.subject(dbName, collName) + " but could not apply it or"
                + " take it back; it is applied before the next write to the collection, or at the next startup",
                failure);
    }

    private static boolean cutBack(File file, long length) {
        PageTails.forget(file);
        return !file.exists() || file.length() == length || FileLocks.truncateTo(file, length);
    }

    private void applyBlanks() {
        for (final var changes : pages.values()) {
            if (changes.blanks.isEmpty()) {
                continue;
            }
            try (var channel = FileChannel.open(changes.file.toPath(), StandardOpenOption.WRITE)) {
                for (final var blank : changes.blanks) {
                    PageHoles.blank(channel, blank[0], blank[1]);
                }
            } catch (IOException e) {
                PageTails.forget(changes.file);
                wal.markUnapplied(dbName, collName);
                logger.error("Committed a write to " + changes.file.getName() + " but could not blank the copies it"
                        + " superseded; they are blanked before the next write to the collection, or at the next"
                        + " startup", e);
                return;
            }
            PageTails.blanked(changes.file);
        }
    }

    static void writeAt(File file, long offset, byte[] bytes, int from, int length) throws IOException {
        try (var channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
            final var buffer = ByteBuffer.wrap(bytes, from, length);
            var position = offset;
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }
}
