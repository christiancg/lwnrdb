package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.unit.structure.JavaSources.JavaSourceFile;

public class PageLengthForgetCoverageTest {
    private static final String FS = "org.techhouse.fs";
    private static final String FS_IO = "org.techhouse.fs.io";
    private static final Pattern REWRITE = Pattern.compile(
            "setLength\\(|\\.truncate\\(|Files\\.move\\(|\\.renameTo\\(|new RandomAccessFile\\([^;]*RW_PERMISSIONS");
    private static final Set<String> NEVER_A_PAGE = Set.of("NodeIdStore.java", "IndexMarkers.java",
            "WriteIntentJournal.java");

    static Set<String> rewritesThatKeepALength(List<JavaSourceFile> files) {
        final var found = new TreeSet<String>();
        for (final var file : files) {
            if (!file.isInPackage(FS) || file.isInPackage(FS_IO) || isNeverAPage(file)) {
                continue;
            }
            final var code = file.text().code();
            if (REWRITE.matcher(code).find() && !code.contains("PageLengths.")) {
                found.add(file.relativePath());
            }
        }
        return found;
    }

    private static boolean isNeverAPage(JavaSourceFile file) {
        return NEVER_A_PAGE.stream().anyMatch(name -> file.relativePath().endsWith("/" + name));
    }

    @Test
    public void test_every_page_rewrite_forgets_the_remembered_length() {
        final var found = rewritesThatKeepALength(JavaSources.under(JavaSources.MAIN));
        assertTrue(found.isEmpty(),
                "these fs classes rewrite, truncate or move a file in place without calling"
                        + " PageLengths, so a remembered page length or line end could outlive the bytes it describes: "
                        + found);
    }

    @Test
    public void test_a_synthetic_rewrite_without_forgetting_is_caught() {
        final var source = JavaSources.parse("org/techhouse/fs/Rewriter.java", """
                package org.techhouse.fs;
                class Rewriter {
                    void cut(java.io.RandomAccessFile file) throws java.io.IOException {
                        file.setLength(0);
                    }
                }
                """);
        final var forgetting = JavaSources.parse("org/techhouse/fs/Forgetter.java", """
                package org.techhouse.fs;
                class Forgetter {
                    void cut(java.io.RandomAccessFile file, java.io.File page) throws java.io.IOException {
                        file.setLength(0);
                        PageLengths.cut(page, 0L);
                    }
                }
                """);
        assertEquals(Set.of("org/techhouse/fs/Rewriter.java"), rewritesThatKeepALength(List.of(source, forgetting)));
    }
}
