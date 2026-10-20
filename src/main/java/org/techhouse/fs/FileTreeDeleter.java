package org.techhouse.fs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;

final class FileTreeDeleter {
    private static final int SWEEPS = 2;

    private FileTreeDeleter() {
    }

    static boolean delete(File root) {
        PageTails.forgetUnder(root);
        for (var sweep = 0; sweep < SWEEPS; sweep++) {
            if (sweep(root)) {
                WriteIntentJournal.forgetUnder(root);
                SyncedWrites.entriesOfLanded(root.getAbsoluteFile().getParentFile().toPath(),
                        "Deleting " + root.getName());
                return true;
            }
        }
        return false;
    }

    private static boolean sweep(File file) {
        if (!Files.isSymbolicLink(file.toPath())) {
            final var children = file.listFiles();
            if (children != null) {
                for (final var child : children) {
                    sweep(child);
                }
            }
        }
        return file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
}
