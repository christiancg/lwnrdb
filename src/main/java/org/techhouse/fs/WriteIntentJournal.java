package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.log.Logger;

final class WriteIntentJournal {
    static final String MARKER_EXTENSION = ".intent";
    private static final Logger logger = Logger.logFor(WriteIntentJournal.class);
    private static final AtomicLong counter = new AtomicLong();
    private static final String HEADER = "INTENT";
    private static final String END = "END";
    private static final String REPLACE = "REPLACE";
    private static final String DELETE = "DELETE";
    private static final String VACUUM = "VACUUM";
    private static final char LINE_END = '\n';
    private static final int HEADER_FIELDS = 4;
    private static final int REPLACE_FIELDS = 8;
    private static final int DELETE_FIELDS = 6;
    private static final int VACUUM_FIELDS = 2;
    private static final int MARKER_SEARCH_DEPTH = 4;
    private static final int MILLIS_DIGITS = 13;
    private static final int COUNTER_DIGITS = 9;
    private static final int LINE_BYTES_HINT = 96;

    private final FilePaths paths;

    record Intent(File file, String dbName, String collName, List<WriteIntent> items) {
    }

    WriteIntentJournal(FilePaths paths) {
        this.paths = paths;
    }

    Intent begin(String dbName, String collName, List<WriteIntent> items) throws IOException {
        final var file = new File(paths.collectionFolder(dbName, collName),
                collName + Globals.FILE_PAGE_SEPARATOR + padded(System.currentTimeMillis(), MILLIS_DIGITS)
                        + Globals.FILE_PAGE_SEPARATOR + padded(counter.incrementAndGet(), COUNTER_DIGITS)
                        + MARKER_EXTENSION);
        final var intent = new Intent(file, dbName, collName, List.copyOf(items));
        final var path = file.toPath();
        OwnerOnly.ensureOwnerOnlyFile(path);
        Files.write(path, encode(intent), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return intent;
    }

    void end(Intent intent) {
        end(intent.file());
    }

    static void end(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException deleteFailure) {
            emptyInPlace(file, deleteFailure);
        }
    }

    private static void emptyInPlace(File file, IOException deleteFailure) {
        OwnerOnly.ensureOwnerOnlyFile(file.toPath());
        try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            writer.setLength(0);
            logger.warning("Could not remove the write intent " + file.getName()
                    + "; emptied it instead, which the next startup discards: " + deleteFailure.getMessage());
        } catch (IOException emptyFailure) {
            deleteFailure.addSuppressed(emptyFailure);
            logger.error("Could not remove or empty the write intent " + file.getName() + "; the next startup"
                    + " recovers it again", deleteFailure);
        }
    }

    private static String padded(long value, int digits) {
        final var text = Long.toString(value);
        return text.length() >= digits ? text : "0".repeat(digits - text.length()) + text;
    }

    static byte[] encode(Intent intent) {
        final var text = new StringBuilder(LINE_BYTES_HINT * (intent.items().size() + 2)).append(HEADER);
        field(text, token(intent.dbName()));
        field(text, token(intent.collName()));
        field(text, intent.items().size()).append(LINE_END);
        for (final var item : intent.items()) {
            switch (item) {
                case WriteIntent.Replace replace -> {
                    field(text.append(REPLACE), token(replace.id()));
                    field(field(field(text, replace.page()), replace.position()), replace.length());
                    field(field(field(text, replace.version()), replace.targetPage()), replace.targetLengthBefore());
                }
                case WriteIntent.Delete delete -> {
                    field(text.append(DELETE), token(delete.id()));
                    field(field(field(field(text, delete.page()), delete.position()), delete.length()),
                            delete.version());
                }
                case WriteIntent.Vacuum vacuum -> field(text.append(VACUUM), vacuum.page());
            }
            text.append(LINE_END);
        }
        return text.append(END).append(LINE_END).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void field(StringBuilder text, String value) {
        text.append(Globals.ID_SEPARATOR).append(value);
    }

    private static StringBuilder field(StringBuilder text, long value) {
        return text.append(Globals.ID_SEPARATOR).append(value);
    }

    private static String token(String value) {
        return FieldIndexEntry.escapeIndexToken(value);
    }

    static Intent decode(File file, byte[] bytes) {
        final var lines = new String(bytes, StandardCharsets.UTF_8).split(String.valueOf(LINE_END), -1);
        if (lines.length < 3 || !lines[lines.length - 1].isEmpty() || !END.equals(lines[lines.length - 2])) {
            return null;
        }
        try {
            final var header = fields(lines[0]);
            if (header.length != HEADER_FIELDS || !HEADER.equals(header[0])
                    || Integer.parseInt(header[3]) != lines.length - 3) {
                return null;
            }
            final var items = new ArrayList<WriteIntent>();
            for (var index = 1; index < lines.length - 2; index++) {
                final var item = decodeItem(fields(lines[index]));
                if (item == null) {
                    return null;
                }
                items.add(item);
            }
            return new Intent(file, FieldIndexEntry.unescapeIndexToken(header[1]),
                    FieldIndexEntry.unescapeIndexToken(header[2]), List.copyOf(items));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String[] fields(String line) {
        return line.split(Globals.ID_SEPARATOR, -1);
    }

    private static WriteIntent decodeItem(String[] fields) {
        return switch (fields[0]) {
            case REPLACE -> fields.length != REPLACE_FIELDS
                    ? null
                    : new WriteIntent.Replace(FieldIndexEntry.unescapeIndexToken(fields[1]), Long.parseLong(fields[2]),
                            Long.parseLong(fields[3]), Long.parseLong(fields[4]), Long.parseLong(fields[5]),
                            Long.parseLong(fields[6]), Long.parseLong(fields[7]));
            case DELETE -> fields.length != DELETE_FIELDS
                    ? null
                    : new WriteIntent.Delete(FieldIndexEntry.unescapeIndexToken(fields[1]), Long.parseLong(fields[2]),
                            Long.parseLong(fields[3]), Long.parseLong(fields[4]), Long.parseLong(fields[5]));
            case VACUUM -> fields.length != VACUUM_FIELDS ? null : new WriteIntent.Vacuum(Long.parseLong(fields[1]));
            default -> null;
        };
    }

    List<File> findMarkerFiles() throws IOException {
        final var root = new File(paths.dbPath()).toPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root, MARKER_SEARCH_DEPTH)) {
            return walk.map(Path::toFile).filter(file -> file.isFile() && isMarker(file))
                    .filter(file -> isUnderLiveFolders(root.toFile(), file)).sorted().toList();
        }
    }

    List<File> findMarkerFiles(String dbName, String collName) {
        final var markers = paths.collectionFolder(dbName, collName).listFiles(file -> file.isFile()
                && file.getName().startsWith(collName + Globals.FILE_PAGE_SEPARATOR) && isMarker(file));
        return markers == null ? List.of() : Arrays.stream(markers).sorted().toList();
    }

    private static boolean isMarker(File file) {
        return file.getName().endsWith(MARKER_EXTENSION);
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
}
