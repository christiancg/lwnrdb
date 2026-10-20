package org.techhouse.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public final class RecordingForcer implements Forcer, AutoCloseable {
    private final List<Path> forced = new CopyOnWriteArrayList<>();
    private final String syncWrites;
    private final String previousSyncWrites = String.valueOf(Configuration.getInstance().isSyncWrites());
    private volatile boolean failFolders;
    private final AtomicInteger foldersBeforeFailing = new AtomicInteger(-1);
    private volatile boolean failFiles;
    private volatile String failingSuffix;

    private RecordingForcer(String syncWrites) {
        this.syncWrites = syncWrites;
    }

    public static RecordingForcer install(boolean syncWrites) throws Exception {
        final var recorder = new RecordingForcer(String.valueOf(syncWrites));
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", recorder.syncWrites);
        TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", recorder);
        return recorder;
    }

    RecordingForcer failingFolders() {
        failFolders = true;
        return this;
    }

    public void failingFor(String nameSuffix) {
        failingSuffix = nameSuffix;
    }

    void failingTheSecondFolderSync() {
        foldersBeforeFailing.set(1);
    }

    RecordingForcer failingFiles() {
        failFiles = true;
        return this;
    }

    @Override
    public void force(FileChannel channel, Path path) throws IOException {
        final var folder = Files.isDirectory(path);
        final var suffix = failingSuffix;
        final var named = suffix != null && String.valueOf(path.getFileName()).endsWith(suffix);
        if (folder && foldersBeforeFailing.getAndDecrement() == 0) {
            throw new IOException("force refused for " + path);
        }
        if (folder && failFolders || !folder && failFiles || named) {
            throw new IOException("force refused for " + path);
        }
        forced.add(path.toAbsolutePath().normalize());
    }

    List<Path> forced() {
        return List.copyOf(forced);
    }

    public List<String> forcedNames() {
        return forced.stream().map(path -> String.valueOf(path.getFileName())).toList();
    }

    public void clear() {
        forced.clear();
    }

    @Override
    public void close() throws Exception {
        TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", Forcer.DEVICE);
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", previousSyncWrites);
    }
}
