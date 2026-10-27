package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

final class PowerLossFixture implements Forcer, AutoCloseable {
    private final Map<Object, byte[]> forcedContent = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> forcedEntries = new ConcurrentHashMap<>();
    private final Map<Object, FileChannel> pinnedFiles = new ConcurrentHashMap<>();
    private final String previousSyncWrites = String.valueOf(Configuration.getInstance().isSyncWrites());

    private PowerLossFixture() {
    }

    static PowerLossFixture install() throws Exception {
        final var fixture = new PowerLossFixture();
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", "true");
        TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", fixture);
        return fixture;
    }

    @Override
    public void force(FileChannel channel, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            final var entries = new HashMap<String, Object>();
            try (var listing = Files.list(path)) {
                for (final var entry : listing.filter(Files::isRegularFile).toList()) {
                    entries.put(String.valueOf(entry.getFileName()), pinnedKeyOf(entry));
                }
            }
            forcedEntries.put(path.toAbsolutePath().normalize(), entries);
            return;
        }
        forcedContent.put(pinnedKeyOf(path), Files.readAllBytes(path));
    }

    void powerLoss() throws IOException {
        powerLoss(Map.of());
    }

    void powerLoss(Map<File, Long> landedPrefixes) throws IOException {
        final var landed = new HashMap<Path, byte[]>();
        for (final var prefix : landedPrefixes.entrySet()) {
            final var bytes = Files.readAllBytes(prefix.getKey().toPath());
            landed.put(prefix.getKey().toPath().toAbsolutePath().normalize(),
                    Arrays.copyOf(bytes, (int) Math.min(bytes.length, prefix.getValue())));
        }
        for (final var folder : forcedEntries.entrySet()) {
            final var durable = folder.getValue();
            try (var listing = Files.list(folder.getKey())) {
                for (final var entry : listing.filter(Files::isRegularFile).toList()) {
                    if (!durable.containsKey(String.valueOf(entry.getFileName()))) {
                        Files.delete(entry);
                    }
                }
            }
            for (final var entry : durable.entrySet()) {
                final var path = folder.getKey().resolve(entry.getKey());
                final var bytes = landed.getOrDefault(path, forcedContent.getOrDefault(entry.getValue(), new byte[0]));
                Files.write(path, bytes);
            }
        }
    }

    static void restart(FilePaths paths, String dbName, String collName) {
        final var root = new File(paths.dbPath());
        WriteAheadLog.forgetUnder(root);
        WriteIntentJournal.forgetUnder(root);
        PageTails.forgetUnder(root);
        TombstoneStore.forgetUnder(root);
        PkIndexStore.forgetState(paths.pkIndexFile(dbName, collName));
    }

    private synchronized Object pinnedKeyOf(Path path) throws IOException {
        final var key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        if (!pinnedFiles.containsKey(key)) {
            pinnedFiles.put(key, FileChannel.open(path, StandardOpenOption.READ));
        }
        return key;
    }

    @Override
    public void close() throws Exception {
        for (final var pinned : pinnedFiles.values()) {
            pinned.close();
        }
        TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", Forcer.DEVICE);
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", previousSyncWrites);
    }
}
