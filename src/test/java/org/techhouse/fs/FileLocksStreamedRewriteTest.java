package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileLocksStreamedRewriteTest {

    @TempDir
    File tmp;

    @Test
    public void streamedRewriteIsAtomic() throws IOException {
        final var target = new File(tmp, "rows.idx");
        Files.writeString(target.toPath(), "original\nrows\n", StandardCharsets.UTF_8);
        final var failure = assertThrows(IOException.class,
                () -> FileLocks.rewriteFileAtomically(target.toPath(), out -> {
                    out.write("partial\n".repeat(10_000).getBytes(StandardCharsets.UTF_8));
                    throw new IOException("disk full");
                }));
        assertEquals("disk full", failure.getMessage());
        assertEquals(List.of("original", "rows"), FileLocks.readAllLinesLocked(target));
        final var survivors = tmp.list((_, name) -> name.endsWith(".idx"));
        assertEquals(1, survivors == null ? 0 : survivors.length);
    }

    @Test
    public void streamedRewriteCreatesOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        final var target = new File(tmp, "fresh.idx");
        FileLocks.rewriteFileAtomically(target.toPath(), out -> out.write('x'));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(target.toPath()));
        assertFalse(new File(tmp, "fresh.idx.repair").exists());
    }

    @Test
    public void listOverloadDelegates() throws IOException {
        final var random = new Random(7L);
        for (var round = 0; round < 200; round++) {
            final var lines = new ArrayList<String>();
            for (var i = random.nextInt(30); i > 0; i--) {
                lines.add(randomLine(random));
            }
            final var target = new File(tmp, "list-" + round + ".idx");
            FileLocks.rewriteFileAtomically(target.toPath(), lines);
            assertArrayEquals(LegacyStorageOracle.joinLines(lines), Files.readAllBytes(target.toPath()));
        }
    }

    private static String randomLine(Random random) {
        final var builder = new StringBuilder();
        for (var i = random.nextInt(20); i > 0; i--) {
            switch (random.nextInt(6)) {
                case 0 -> builder.append('\uD83D');
                case 1 -> builder.append('\uDE00');
                case 2 -> builder.append("😀");
                case 3 -> builder.append('é');
                case 4 -> builder.append('\u001F');
                default -> builder.append((char) ('a' + random.nextInt(26)));
            }
        }
        return builder.toString();
    }
}
