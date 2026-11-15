package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.techhouse.test.PlatformSupport;

public class PlatformSupportTest {
    @Test
    public void test_a_posix_build_never_skips_the_synced_or_inode_keyed_tests() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        assertTrue(PlatformSupport.directorySync(),
                "the folder-sync probe failed, so every @RequiresDirectorySync test would be skipped silently");
        assertTrue(PlatformSupport.fileKeys(),
                "the file-key probe failed, so every @RequiresFileKeys test would be skipped silently");
    }
}
