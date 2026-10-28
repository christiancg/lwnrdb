package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class WalCoverageTest {
    private static final String FS = "org.techhouse.fs";
    private static final String FS_IO = "org.techhouse.fs.io";
    private static final Pattern WRITE = Pattern.compile("new (java\\.io\\.)?FileOutputStream\\("
            + "|FileChannel\\.open\\([^;]*WRITE|Files\\.write|Files\\.newOutputStream|Files\\.move\\("
            + "|new (java\\.io\\.)?RandomAccessFile\\([^;]*RW_PERMISSIONS|\\.renameTo\\(|setLength\\(|\\.truncate\\("
            + "|truncateTo\\(|moveIntoPlace\\(|rewriteFileAtomically\\(|writeAt\\(|PageHoles\\.blank");
    private static final Pattern PAGE_OR_PK = Pattern.compile("collectionPage\\(|pkIndexFile\\(");
    private static final Set<String> ALLOWED = Set.of("src/main/java/org/techhouse/fs/WalPlan.java",
            "src/main/java/org/techhouse/fs/WalRedo.java", "src/main/java/org/techhouse/fs/WriteAheadLog.java",
            "src/main/java/org/techhouse/fs/PageVacuum.java", "src/main/java/org/techhouse/fs/PkIndexStore.java",
            "src/main/java/org/techhouse/fs/TornPageTail.java",
            "src/main/java/org/techhouse/fs/OrphanedPageRecords.java");

    static Set<String> pageAndPkWriters(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(FS) || file.isInPackage(FS_IO)) {
                continue;
            }
            final var code = file.text().code();
            if (WRITE.matcher(code).find() && PAGE_OR_PK.matcher(code).find()) {
                found.add(file.relativePath());
            }
        }
        return found;
    }

    @Test
    public void test_only_the_allow_listed_classes_write_a_page_or_the_pk_index() {
        assertEquals(ALLOWED, pageAndPkWriters(JavaSources.under(JavaSources.MAIN)),
                "a page or pk.idx written outside"
                        + " WalPlan is not in the write-ahead log, so a power loss can lose it after the write was"
                        + " acknowledged; log it through WalPlan, or bracket it with checkpoints and list it here");
    }

    @Test
    public void test_a_synthetic_page_writer_is_caught() {
        final var writer = JavaSources.parse("org/techhouse/fs/Writer.java", """
                package org.techhouse.fs;
                class Writer {
                    void write(FilePaths paths) throws Exception {
                        WalPlan.writeAt(paths.collectionPage("d", "c", 0), 0L, new byte[1], 0, 1);
                    }
                }
                """);
        final var reader = JavaSources.parse("org/techhouse/fs/Reader.java", """
                package org.techhouse.fs;
                class Reader {
                    Object read(FilePaths paths) throws Exception {
                        return java.nio.file.Files.readAllBytes(paths.pkIndexFile("d", "c").toPath());
                    }
                }
                """);

        assertEquals(Set.of("org/techhouse/fs/Writer.java"), pageAndPkWriters(List.of(writer, reader)));
    }
}
