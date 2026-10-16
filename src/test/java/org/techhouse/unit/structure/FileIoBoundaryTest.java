package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class FileIoBoundaryTest {
    private static final String FS = "org.techhouse.fs";
    private static final String FS_IO = "org.techhouse.fs.io";
    private static final Pattern FILE_ACCESS = Pattern.compile("\\b(java\\.nio\\.file\\.Files|Files\\.|"
            + "java\\.io\\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)\\b|"
            + "FileChannel|new File\\()");
    private static final Pattern PROJECT_REFERENCE = Pattern.compile("\\borg\\.techhouse\\.(?!fs\\.io\\b)[\\w.]+");

    static Set<String> fileAccessOutsideFs(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(FS)) {
                final var matcher = FILE_ACCESS.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    static Set<String> fsIoReachesNoProjectPackage(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (file.isInPackage(FS_IO)) {
                final var matcher = PROJECT_REFERENCE.matcher(file.text().code());
                while (matcher.find()) {
                    found.add(file.relativePath() + " -> " + matcher.group());
                }
            }
        }
        return found;
    }

    @Test
    public void test_every_file_read_and_write_happens_in_fs() {
        final var found = fileAccessOutsideFs(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "only org.techhouse.fs may touch the filesystem; route it through fs.io: " + found);
    }

    @Test
    public void test_fs_io_imports_nothing_from_the_project() {
        final var found = fsIoReachesNoProjectPackage(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(), "fs.io is a leaf and may use only java.*: " + found);
    }

    @Test
    public void test_a_synthetic_class_outside_fs_is_caught_on_every_branch() {
        final var source = JavaSources.parse("Config.java", """
                package org.techhouse.config;
                import java.nio.file.Files;
                import java.io.File;
                import java.io.FileInputStream;
                import java.io.FileOutputStream;
                import java.io.FileReader;
                import java.io.FileWriter;
                import java.io.RandomAccessFile;
                import java.nio.channels.FileChannel;
                class Config {
                    Object a = Files.exists(null);
                    Object b = new File("x");
                }
                """);
        assertEquals(Set.of("Config.java -> java.nio.file.Files", "Config.java -> Files.",
                "Config.java -> java.io.File", "Config.java -> java.io.FileInputStream",
                "Config.java -> java.io.FileOutputStream", "Config.java -> java.io.FileReader",
                "Config.java -> java.io.FileWriter", "Config.java -> java.io.RandomAccessFile",
                "Config.java -> FileChannel", "Config.java -> new File("), fileAccessOutsideFs(List.of(source)));
    }

    @Test
    public void test_values_comments_and_fs_itself_are_not_caught() {
        final var values = JavaSources.parse("Paths.java", """
                package org.techhouse.config;
                import java.nio.file.Path;
                import java.nio.file.FileSystems;
                import org.techhouse.fs.io.OwnerOnlyFiles;
                class Paths {
                    // Files.exists is not called here
                    Object a = Path.of("x");
                    Object b = FileSystems.getDefault().getSeparator();
                    Object c = OwnerOnlyFiles.supported();
                }
                """);
        final var fs = JavaSources.parse("Store.java", """
                package org.techhouse.fs;
                import java.nio.file.Files;
                class Store { Object a = Files.exists(null); }
                """);
        assertEquals(Set.of(), fileAccessOutsideFs(List.of(values, fs)));
    }

    @Test
    public void test_a_synthetic_fs_io_class_reaching_the_project_is_caught() {
        final var leaf = JavaSources.parse("Leaf.java", """
                package org.techhouse.fs.io;
                import org.techhouse.log.Logger;
                import org.techhouse.fs.io.OwnerOnlyFiles.Creation;
                class Leaf { org.techhouse.config.Configuration c; }
                """);
        assertEquals(Set.of("Leaf.java -> org.techhouse.log.Logger", "Leaf.java -> org.techhouse.config.Configuration"),
                fsIoReachesNoProjectPackage(List.of(leaf)));
    }
}
