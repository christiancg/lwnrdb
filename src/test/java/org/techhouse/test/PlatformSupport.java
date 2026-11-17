package org.techhouse.test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class PlatformSupport {
    private static final boolean DIRECTORY_SYNC = forcesAFolder();
    private static final boolean FILE_KEYS = keysAFile();

    private PlatformSupport() {
    }

    public static boolean directorySync() {
        return DIRECTORY_SYNC;
    }

    public static boolean fileKeys() {
        return FILE_KEYS;
    }

    private static boolean forcesAFolder() {
        try {
            final var folder = Files.createTempDirectory("lwnrdb-folder-sync");
            try (var channel = FileChannel.open(folder, StandardOpenOption.READ)) {
                channel.force(false);
                return true;
            } finally {
                Files.delete(folder);
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean keysAFile() {
        try {
            final var file = Files.createTempFile("lwnrdb-file-key", ".probe");
            try {
                return Files.readAttributes(file, BasicFileAttributes.class).fileKey() != null;
            } finally {
                Files.delete(file);
            }
        } catch (IOException e) {
            return false;
        }
    }
}
