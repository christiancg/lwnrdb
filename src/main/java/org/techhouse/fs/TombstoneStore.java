package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileLocks.Parsed;
import org.techhouse.fs.ResidentTombstones.FileState;
import org.techhouse.log.Logger;

public final class TombstoneStore {
    private static final Logger logger = Logger.logFor(TombstoneStore.class);
    private static final String TOMBSTONE_ROLLBACK_FAILURE = "Could not roll back a failed tombstone append; the"
            + " tombstone file may hold a torn line, and nothing rebuilds it";
    private static final Map<String, ResidentTombstones> residents = new ConcurrentHashMap<>();

    private final FilePaths paths;

    TombstoneStore(FilePaths paths) {
        this.paths = paths;
    }

    public void append(String dbName, String collName, String id, long version) throws IOException {
        appendAll(dbName, collName, List.of(id), List.of(version));
    }

    public void appendAll(String dbName, String collName, List<String> ids, List<Long> versions) throws IOException {
        appendResident(paths.tombstoneFile(dbName, collName), ids, versions, true);
    }

    public PendingForce appendAllDeferred(String dbName, String collName, List<String> ids, List<Long> versions)
            throws IOException {
        if (ids.isEmpty()) {
            return PendingForce.NONE;
        }
        final var file = paths.tombstoneFile(dbName, collName);
        appendResident(file, ids, versions, false);
        return PendingForce.start(file.toPath());
    }

    public void retract(String dbName, String collName, String id, long version) throws IOException {
        retract(paths.tombstoneFile(dbName, collName), id, version);
    }

    public void appendAdmin(String key, long version) throws IOException {
        final var file = paths.adminTombstoneFile();
        MetadataFileStore.ensureFolder(file.getParentFile(), "cluster", "admin");
        FileLocks.appendLines(file, List.of(key), tombstoned -> lineOf(tombstoned, version),
                TOMBSTONE_ROLLBACK_FAILURE);
    }

    public Map<String, Long> readAdmin() throws IOException {
        return read(paths.adminTombstoneFile());
    }

    public Map<String, Long> read(String dbName, String collName) throws IOException {
        return Collections.unmodifiableMap(new HashMap<>(residentOf(paths.tombstoneFile(dbName, collName)).versions()));
    }

    public long versionOf(String dbName, String collName, String id) throws IOException {
        final var version = residentOf(paths.tombstoneFile(dbName, collName)).versions().get(id);
        return version == null ? 0L : version;
    }

    public TombstoneView view(String dbName, String collName) throws IOException {
        return residentOf(paths.tombstoneFile(dbName, collName)).view();
    }

    public long highestVersion(String dbName, String collName) throws IOException {
        var highest = 0L;
        for (final var version : read(paths.tombstoneFile(dbName, collName)).values()) {
            highest = Math.max(highest, version);
        }
        return highest;
    }

    public void compact(String dbName, String collName, long minVersionToKeep) throws IOException {
        compact(paths.tombstoneFile(dbName, collName), minVersionToKeep);
    }

    public void compactIfDue(String dbName, String collName, long minVersionToKeep) throws IOException {
        final var file = paths.tombstoneFile(dbName, collName);
        final var resident = residentOf(file);
        final var oldest = resident.oldest();
        if (oldest != 0L && oldest < minVersionToKeep || resident.lineCount() > 2L * resident.versions().size()) {
            compact(file, minVersionToKeep);
        }
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        residents.keySet().removeIf(path -> path.startsWith(prefix));
    }

    private static String lineOf(String id, long version) {
        return FieldIndexEntry.escapeIndexToken(id) + Globals.ID_SEPARATOR + version;
    }

    static void appendResident(File file, List<String> ids, List<Long> versions, boolean forced) throws IOException {
        if (ids.isEmpty()) {
            return;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var resident = loadLocked(file);
            final var lines = new ArrayList<String>(ids.size());
            for (var i = 0; i < ids.size(); i++) {
                lines.add(lineOf(ids.get(i), versions.get(i)));
            }
            if (forced) {
                FileLocks.appendLines(file, lines, line -> line, TOMBSTONE_ROLLBACK_FAILURE);
            } else {
                FileLocks.appendLinesUnforced(file, lines, line -> line, TOMBSTONE_ROLLBACK_FAILURE);
            }
            if (resident != null) {
                resident.merge(ids, versions, FileState.of(file));
            }
        } finally {
            lock.unlock();
        }
    }

    static Map<String, Long> read(File file) throws IOException {
        if (!file.exists()) {
            return new HashMap<>();
        }
        final var parsed = FileLocks.readHealing(file, TombstoneStore::parseTombstones);
        if (parsed.unrecognised()) {
            logUnrecognisedRead(file);
        }
        return parsed.entries();
    }

    private static void logUnrecognisedRead(File file) {
        logger.error("No line in " + file.getName() + " could be read as a tombstone entry, so it is not the"
                + " file this loader expects; leaving it untouched. Nothing rebuilds the tombstone file, so it"
                + " must never be rewritten from a read that understood none of it.");
    }

    static ResidentTombstones residentOf(File file) throws IOException {
        final var cached = residents.get(file.getAbsolutePath());
        if (cached != null && cached.describes(FileState.of(file))) {
            return cached;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var loaded = loadLocked(file);
            return loaded != null ? loaded : ResidentTombstones.unrecognised();
        } finally {
            lock.unlock();
        }
    }

    private static ResidentTombstones loadLocked(File file) throws IOException {
        final var key = file.getAbsolutePath();
        final var state = FileState.of(file);
        final var cached = residents.get(key);
        if (cached != null && cached.describes(state)) {
            return cached;
        }
        if (!state.exists()) {
            final var empty = ResidentTombstones.of(new HashMap<>(), 0, state);
            residents.put(key, empty);
            return empty;
        }
        var parsed = parseTombstones(file);
        if (parsed.unrecognised()) {
            residents.remove(key);
            logUnrecognisedRead(file);
            return null;
        }
        if (parsed.dropped()) {
            FileLocks.rewriteFileAtomically(file.toPath(), parsed.lines());
        }
        final var loaded = ResidentTombstones.of(parsed.entries(), parsed.lines().size(), FileState.of(file));
        residents.put(key, loaded);
        return loaded;
    }

    static void compact(File file, long minVersionToKeep) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var parsed = parseTombstones(file);
            if (parsed.unrecognised()) {
                residents.remove(file.getAbsolutePath());
                logger.error("No line in " + file.getName() + " could be read as a tombstone entry; leaving it"
                        + " untouched rather than compacting it to nothing");
                return;
            }
            final var kept = new LinkedHashMap<String, Long>();
            for (final var entry : parsed.entries().entrySet()) {
                if (entry.getValue() >= minVersionToKeep) {
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            final var lines = new ArrayList<String>(kept.size());
            for (final var entry : kept.entrySet()) {
                lines.add(lineOf(entry.getKey(), entry.getValue()));
            }
            publishRewrite(file, new HashMap<>(kept), lines);
        } finally {
            lock.unlock();
        }
    }

    static void retract(File file, String id, long version) throws IOException {
        if (!file.exists()) {
            return;
        }
        final var lock = FileLocks.lockWrite(file);
        try {
            final var parsed = parseTombstones(file);
            if (parsed.unrecognised()) {
                residents.remove(file.getAbsolutePath());
                logger.error("No line in " + file.getName() + " could be read as a tombstone entry; leaving it"
                        + " untouched rather than retracting from it");
                return;
            }
            final var retracted = lineOf(id, version);
            final var kept = parsed.lines().stream().filter(line -> !line.equals(retracted)).toList();
            if (kept.size() != parsed.lines().size()) {
                publishRewrite(file, entriesOf(kept), kept);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void publishRewrite(File file, Map<String, Long> entries, List<String> lines) throws IOException {
        final var key = file.getAbsolutePath();
        residents.remove(key);
        FileLocks.rewriteFileAtomically(file.toPath(), lines);
        residents.put(key, ResidentTombstones.of(entries, lines.size(), FileState.of(file)));
    }

    private static Map<String, Long> entriesOf(List<String> lines) {
        final var entries = new HashMap<String, Long>(lines.size() * 2);
        for (final var line : lines) {
            final var sep = line.indexOf(Globals.ID_SEPARATOR);
            entries.merge(FieldIndexEntry.unescapeIndexToken(line.substring(0, sep)),
                    Long.parseLong(line.substring(sep + Globals.ID_SEPARATOR.length())), Math::max);
        }
        return entries;
    }

    private static Parsed<Map<String, Long>> parseTombstones(File file) throws IOException {
        final var entries = new HashMap<String, Long>();
        final var lines = new ArrayList<String>();
        var dropped = false;
        for (final var line : FileLocks.decodeLines(Files.readAllBytes(file.toPath()))) {
            final var cleaned = line.replace("\r", "").replace("\n", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            final var sep = cleaned.indexOf(Globals.ID_SEPARATOR);
            if (sep <= 0) {
                dropped = true;
                logger.warning("Removing malformed tombstone entry in " + file.getName() + ": no separator found");
                continue;
            }
            try {
                final var id = FieldIndexEntry.unescapeIndexToken(cleaned.substring(0, sep));
                final var version = Long.parseLong(cleaned.substring(sep + Globals.ID_SEPARATOR.length()));
                entries.merge(id, version, Math::max);
                lines.add(cleaned);
            } catch (NumberFormatException e) {
                dropped = true;
                logger.warning("Removing malformed tombstone entry in " + file.getName() + ": " + e.getMessage());
            }
        }
        return new Parsed<>(entries, lines, dropped, dropped && entries.isEmpty());
    }
}
