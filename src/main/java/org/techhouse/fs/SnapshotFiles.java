package org.techhouse.fs;

public final class SnapshotFiles {
    private final FilePaths paths;
    private final PkIndexStore pkIndexStore;

    SnapshotFiles(FilePaths paths, PkIndexStore pkIndexStore) {
        this.paths = paths;
        this.pkIndexStore = pkIndexStore;
    }

    public void onRetiredCopies(RetiredCopies listener) {
        pkIndexStore.onRetiredCopies(listener);
    }

    public SnapshotImageFile images(String snapshotId, String collName) {
        return new SnapshotImageFile(paths.snapshotImages(snapshotId, collName));
    }

    public boolean deleteSnapshotFolder(String snapshotId) {
        final var folder = paths.snapshotFolder(snapshotId);
        return !folder.exists() || FileTreeDeleter.delete(folder);
    }

    public boolean clearSnapshotFolders() {
        final var folder = paths.snapshotsFolder();
        return !folder.exists() || FileTreeDeleter.delete(folder);
    }
}
