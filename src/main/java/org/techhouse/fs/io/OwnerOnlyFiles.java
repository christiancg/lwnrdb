package org.techhouse.fs.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Set;

public final class OwnerOnlyFiles {
    private static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OTHERS = PosixFilePermissions.fromString("---rwxrwx");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    public enum Creation {
        OWNER_ONLY, DEFAULT_PERMISSIONS, NOT_SUPPORTED, ALREADY_EXISTED
    }

    private OwnerOnlyFiles() {
    }

    public static boolean supported() {
        return POSIX;
    }

    public static Creation createDirectories(Path directory) throws IOException {
        return createDirectories(directory, POSIX);
    }

    static Creation createDirectories(Path directory, boolean posix) throws IOException {
        if (posix) {
            try {
                createMissingLevelsOwnerOnly(directory.toAbsolutePath());
                return Creation.OWNER_ONLY;
            } catch (IOException | UnsupportedOperationException e) {
                Files.createDirectories(directory);
                return Creation.DEFAULT_PERMISSIONS;
            }
        }
        Files.createDirectories(directory);
        return Creation.NOT_SUPPORTED;
    }

    public static boolean createDirectory(Path directory) {
        return createDirectory(directory, POSIX);
    }

    static boolean createDirectory(Path directory, boolean posix) {
        try {
            if (posix) {
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY));
            } else {
                Files.createDirectory(directory);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void createMissingLevelsOwnerOnly(Path absolute) throws IOException {
        final var missing = new ArrayDeque<Path>();
        for (var level = absolute; level != null && !Files.exists(level); level = level.getParent()) {
            missing.push(level);
        }
        while (!missing.isEmpty()) {
            final var level = missing.pop();
            try {
                Files.createDirectory(level, PosixFilePermissions.asFileAttribute(DIRECTORY));
            } catch (FileAlreadyExistsException raced) {
                if (!Files.isDirectory(level)) {
                    throw raced;
                }
            }
        }
    }

    public static OutputStream newOwnerOnlyOutputStream(Path file) throws IOException {
        return newOwnerOnlyOutputStream(file, POSIX);
    }

    static OutputStream newOwnerOnlyOutputStream(Path file, boolean posix) throws IOException {
        ensureOwnerOnlyFile(file, posix);
        return Files.newOutputStream(file);
    }

    public static Creation ensureOwnerOnlyFile(Path file) {
        return ensureOwnerOnlyFile(file, POSIX);
    }

    static Creation ensureOwnerOnlyFile(Path file, boolean posix) {
        if (!posix) {
            return Creation.NOT_SUPPORTED;
        }
        if (Files.exists(file)) {
            return Creation.ALREADY_EXISTED;
        }
        return createOwnerOnlyFile(file);
    }

    public static Creation createNewOwnerOnlyFile(Path file) throws IOException {
        if (Files.exists(file)) {
            return Creation.ALREADY_EXISTED;
        }
        final var creation = POSIX ? createOwnerOnlyFile(file) : Creation.NOT_SUPPORTED;
        return Files.exists(file) || file.toFile().createNewFile() ? creation : Creation.ALREADY_EXISTED;
    }

    private static Creation createOwnerOnlyFile(Path file) {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE));
            return Creation.OWNER_ONLY;
        } catch (IOException | UnsupportedOperationException e) {
            return Files.exists(file) ? Creation.ALREADY_EXISTED : Creation.DEFAULT_PERMISSIONS;
        }
    }

    public static boolean isAccessibleByOthers(Path path) {
        return isAccessibleByOthers(path, POSIX);
    }

    static boolean isAccessibleByOthers(Path path, boolean posix) {
        if (!posix) {
            return false;
        }
        try {
            return Files.getPosixFilePermissions(path).stream().anyMatch(OTHERS::contains);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
