package org.techhouse.fs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class WriteIntentJournal {
    static final long COMPACTION_BYTES = 64 * 1024;
    private static final Logger logger = Logger.logFor(WriteIntentJournal.class);
    private static final Map<String, LogState> states = new ConcurrentHashMap<>();
    private static final int LOG_SEARCH_DEPTH = 4;

    private final FilePaths paths;

    record Intent(File log, long seq, String dbName, String collName, List<WriteIntent> items) {
        String label() {
            return log.getName() + "#" + seq;
        }
    }

    private static final class LogState {
        private final Set<Long> open = new HashSet<>();
        private long nextSeq = 1L;
        private long knownLength;
    }

    WriteIntentJournal(FilePaths paths) {
        this.paths = paths;
    }

    Intent begin(String dbName, String collName, List<WriteIntent> items) throws IOException {
        final var log = paths.intentLog(dbName, collName);
        final Intent intent;
        final var lock = FileLocks.lockWrite(log);
        try {
            final var state = stateOf(log);
            intent = new Intent(log, state.nextSeq, dbName, collName, List.copyOf(items));
            append(log, state, IntentLogCodec.encode(intent));
            state.nextSeq++;
            state.open.add(intent.seq());
        } finally {
            lock.unlock();
        }
        final var group = new ForceGroup();
        group.add(log.toPath());
        try {
            group.force();
        } catch (IOException e) {
            end(intent);
            throw e;
        }
        return intent;
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        states.keySet().removeIf(path -> path.startsWith(prefix));
    }

    void end(Intent intent) {
        final var log = intent.log();
        final var lock = FileLocks.lockWrite(log);
        try {
            final var state = stateOf(log);
            append(log, state, IntentLogCodec.encodeDone(intent.seq()));
            state.open.remove(intent.seq());
            settle(log, state);
        } catch (IOException e) {
            logger.error("Could not mark the write intent " + intent.label() + " done; the next startup recovers it"
                    + " again", e);
        } finally {
            lock.unlock();
        }
    }

    List<Intent> openIntents(String dbName, String collName) throws IOException {
        return openIntents(paths.intentLog(dbName, collName));
    }

    List<Intent> openIntents(File log) throws IOException {
        final var lock = FileLocks.lockWrite(log);
        try {
            stateOf(log);
            return log.exists() ? IntentLogCodec.parse(log, Files.readAllBytes(log.toPath())).open() : List.of();
        } finally {
            lock.unlock();
        }
    }

    List<File> findLogs() throws IOException {
        final var root = new File(paths.dbPath()).toPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root, LOG_SEARCH_DEPTH)) {
            return walk.map(Path::toFile).filter(file -> file.isFile() && isLog(file))
                    .filter(file -> isUnderLiveFolders(root.toFile(), file)).sorted().toList();
        }
    }

    private static LogState stateOf(File log) throws IOException {
        final var state = states.computeIfAbsent(log.getAbsolutePath(), _ -> new LogState());
        final var length = log.exists() ? log.length() : 0L;
        if (length != state.knownLength) {
            reseed(log, state);
        }
        return state;
    }

    private static void reseed(File log, LogState state) throws IOException {
        state.open.clear();
        if (!log.exists()) {
            state.knownLength = 0L;
            return;
        }
        final var bytes = Files.readAllBytes(log.toPath());
        final var parsed = IntentLogCodec.parse(log, bytes);
        if (parsed.cleanLength() != bytes.length) {
            logger.warning("Discarding the torn end of the write intent log " + log.getPath() + ": it was torn while"
                    + " being written, which happens before any page is touched");
            FileLocks.truncateTo(log, parsed.cleanLength(), "Could not cut the torn end of the write intent log "
                    + log.getPath() + "; the next startup cuts it again");
        }
        parsed.open().forEach(intent -> state.open.add(intent.seq()));
        state.nextSeq = Math.max(state.nextSeq, parsed.lastSeq() + 1);
        state.knownLength = log.length();
    }

    private static void append(File log, LogState state, byte[] bytes) throws IOException {
        if (state.knownLength == 0L) {
            OwnerOnly.ensureOwnerOnlyFile(log.toPath());
        }
        var appended = false;
        try {
            try (var out = new FileOutputStream(log, true)) {
                out.write(bytes);
            }
            appended = true;
        } finally {
            if (!appended && log.length() != state.knownLength) {
                FileLocks.truncateTo(log, state.knownLength, "Could not roll back a partial append to the write"
                        + " intent log " + log.getPath() + "; the next startup cuts it");
            }
        }
        state.knownLength += bytes.length;
    }

    private static void settle(File log, LogState state) throws IOException {
        if (state.open.isEmpty()) {
            try (var channel = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
                channel.truncate(0L);
            }
            state.knownLength = log.length();
        } else if (state.knownLength > COMPACTION_BYTES) {
            compact(log, state);
        }
    }

    private static void compact(File log, LogState state) throws IOException {
        final var open = IntentLogCodec.parse(log, Files.readAllBytes(log.toPath())).open();
        final var content = new ByteArrayOutputStream();
        for (final var intent : open) {
            content.write(IntentLogCodec.encode(intent));
        }
        FileLocks.rewriteFileAtomically(log.toPath(), content.toByteArray());
        state.open.clear();
        open.forEach(intent -> state.open.add(intent.seq()));
        state.knownLength = content.size();
    }

    private static boolean isLog(File file) {
        return file.getName().endsWith(Globals.INTENT_LOG_EXTENSION);
    }

    static boolean isUnderLiveFolders(File root, File log) {
        for (var folder = log.getParentFile(); folder != null
                && !folder.equals(root); folder = folder.getParentFile()) {
            if (!FolderQuarantine.isLiveFolder(folder)) {
                return false;
            }
        }
        return true;
    }
}
