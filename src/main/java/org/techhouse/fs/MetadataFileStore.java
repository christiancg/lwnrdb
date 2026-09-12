package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MetadataFileStore {
    private MetadataFileStore() {
    }

    static void write(File file, String json) throws IOException {
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try {
            FileLocks.rewriteFileAtomically(file.toPath(), List.of(json));
        } finally {
            lock.unlock();
        }
    }

    static String read(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        return String.join("", FileLocks.readAllLinesLocked(file));
    }

    static boolean delete(File file) {
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        try {
            return file.exists() && file.delete();
        } finally {
            lock.unlock();
        }
    }

    static void ensureFolder(File folder, String label, String dbName) throws IOException {
        if (!folder.exists() && !folder.mkdirs() && !folder.exists()) {
            throw new IOException("Could not create the " + label + " folder for database " + dbName);
        }
    }

    static List<String> listNames(File folder, String extension) {
        final var files = folder.listFiles();
        if (files == null) {
            return List.of();
        }
        final var names = new ArrayList<String>();
        for (final var file : files) {
            final var fileName = file.getName();
            if (file.isFile() && fileName.endsWith(extension)) {
                names.add(fileName.substring(0, fileName.length() - extension.length()));
            }
        }
        Collections.sort(names);
        return names;
    }
}
