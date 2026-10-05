package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class CompactionJournal {
    static final String MARKER_EXTENSION = ".compacting";
    static final long NO_TARGET = -1L;
    private static final Logger logger = Logger.logFor(CompactionJournal.class);
    private static final byte HEADER_END = '\n';
    private static final int HEADER_FIELDS = 11;
    private static final int MARKER_SEARCH_DEPTH = 4;

    enum Kind {
        DELETE, UPDATE, RELOCATE
    }

    record Marker(Kind kind, String dbName, String collName, long page, long position, long originalLength,
            long removedLength, String id, long version, long targetPage, long targetLengthBefore, byte[] tail) {

        PkIndexEntry preOpEntry() {
            return new PkIndexEntry(dbName, collName, id, position, removedLength, page, version);
        }

        Marker targeting(long newTargetPage, long newTargetLengthBefore) {
            return new Marker(kind, dbName, collName, page, position, originalLength, removedLength, id, version,
                    newTargetPage, newTargetLengthBefore, tail);
        }
    }

    private final FilePaths paths;

    CompactionJournal(FilePaths paths) {
        this.paths = paths;
    }

    Marker begin(Kind kind, PkIndexEntry removed, long originalLength, byte[] tail) throws IOException {
        final var marker = new Marker(kind, removed.getDatabaseName(), removed.getCollectionName(), removed.getPage(),
                removed.getPosition(), originalLength, removed.getLength(), removed.getValue(), removed.getVersion(),
                NO_TARGET, 0L, tail);
        Files.write(markerFile(marker).toPath(), encode(marker), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return marker;
    }

    Marker retarget(Marker marker, long targetPage, long targetLengthBefore) throws IOException {
        final var retargeted = marker.targeting(targetPage, targetLengthBefore);
        FileLocks.rewriteFileAtomically(markerFile(retargeted).toPath(), encode(retargeted));
        return retargeted;
    }

    void end(Marker marker) {
        final var file = markerFile(marker);
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            logger.error("Could not remove the compaction marker " + file.getName() + "; the next startup replays"
                    + " it, which lands on the same consistent state", e);
        }
    }

    File markerFile(Marker marker) {
        return markerFile(marker.dbName(), marker.collName(), marker.page());
    }

    File markerFile(String dbName, String collName, long page) {
        return new File(paths.collectionFolder(dbName, collName),
                collName + Globals.FILE_PAGE_SEPARATOR + page + MARKER_EXTENSION);
    }

    List<File> findMarkerFiles() throws IOException {
        final var root = new File(paths.dbPath()).toPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root, MARKER_SEARCH_DEPTH)) {
            return walk.map(java.nio.file.Path::toFile)
                    .filter(file -> file.isFile() && file.getName().endsWith(MARKER_EXTENSION))
                    .filter(file -> isUnderLiveFolders(root.toFile(), file)).sorted().toList();
        }
    }

    private static boolean isUnderLiveFolders(File root, File marker) {
        for (var folder = marker.getParentFile(); folder != null
                && !folder.equals(root); folder = folder.getParentFile()) {
            if (!FolderQuarantine.isLiveFolder(folder)) {
                return false;
            }
        }
        return true;
    }

    static byte[] encode(Marker marker) {
        final var header = String.join(Globals.ID_SEPARATOR, marker.kind().name(),
                FieldIndexEntry.escapeIndexToken(marker.dbName()), FieldIndexEntry.escapeIndexToken(marker.collName()),
                Long.toString(marker.page()), Long.toString(marker.position()), Long.toString(marker.originalLength()),
                Long.toString(marker.removedLength()), FieldIndexEntry.escapeIndexToken(marker.id()),
                Long.toString(marker.version()), Long.toString(marker.targetPage()),
                Long.toString(marker.targetLengthBefore()));
        final var headerBytes = header.getBytes(StandardCharsets.UTF_8);
        final var encoded = new byte[headerBytes.length + 1 + marker.tail().length];
        System.arraycopy(headerBytes, 0, encoded, 0, headerBytes.length);
        encoded[headerBytes.length] = HEADER_END;
        System.arraycopy(marker.tail(), 0, encoded, headerBytes.length + 1, marker.tail().length);
        return encoded;
    }

    static Marker decode(byte[] bytes) {
        final var headerEnd = indexOfHeaderEnd(bytes);
        if (headerEnd < 0) {
            return null;
        }
        final var fields = new String(bytes, 0, headerEnd, StandardCharsets.UTF_8).split(Globals.ID_SEPARATOR, -1);
        if (fields.length != HEADER_FIELDS) {
            return null;
        }
        try {
            final var tail = Arrays.copyOfRange(bytes, headerEnd + 1, bytes.length);
            final var marker = new Marker(Kind.valueOf(fields[0]), FieldIndexEntry.unescapeIndexToken(fields[1]),
                    FieldIndexEntry.unescapeIndexToken(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4]),
                    Long.parseLong(fields[5]), Long.parseLong(fields[6]), FieldIndexEntry.unescapeIndexToken(fields[7]),
                    Long.parseLong(fields[8]), Long.parseLong(fields[9]), Long.parseLong(fields[10]), tail);
            return isWhole(marker) ? marker : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isWhole(Marker marker) {
        return marker.position() >= 0 && marker.removedLength() > 0
                && marker.tail().length == marker.originalLength() - marker.position()
                && marker.removedLength() <= marker.tail().length;
    }

    private static int indexOfHeaderEnd(byte[] bytes) {
        for (var i = 0; i < bytes.length; i++) {
            if (bytes[i] == HEADER_END) {
                return i;
            }
        }
        return -1;
    }
}
