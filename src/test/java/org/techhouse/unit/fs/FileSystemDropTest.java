package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestUtils;

public class FileSystemDropTest {
    private final Configuration config = Configuration.getInstance();
    private String origFilePath;

    @BeforeEach
    public void setUp() {
        origFilePath = config.getFilePath();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "filePath", origFilePath);
    }

    private FileSystem fileSystemRootedAt(Path base) throws Exception {
        TestUtils.setPrivateField(config, "filePath", base.toString());
        final var fs = new FileSystem();
        fs.createBaseDbPath();
        return fs;
    }

    @Test
    public void test_dropping_a_database_whose_folder_is_a_dangling_link_removes_the_link(@TempDir Path tmp)
            throws Exception {
        final var fs = fileSystemRootedAt(tmp.resolve("db"));
        final var link = Files.createSymbolicLink(tmp.resolve("db").resolve("shop"), tmp.resolve("gone"));

        assertTrue(fs.deleteDatabase("shop"));
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void test_dropping_a_collection_whose_folder_is_a_dangling_link_removes_the_link(@TempDir Path tmp)
            throws Exception {
        final var fs = fileSystemRootedAt(tmp.resolve("db"));
        Files.createDirectories(tmp.resolve("db").resolve("shop"));
        final var link = Files.createSymbolicLink(tmp.resolve("db").resolve("shop").resolve("orders"),
                tmp.resolve("gone"));

        assertTrue(fs.deleteCollectionFiles("shop", "orders"));
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void test_dropping_a_missing_database_is_still_a_no_op(@TempDir Path tmp) throws Exception {
        assertTrue(fileSystemRootedAt(tmp.resolve("db")).deleteDatabase("absent"));
    }
}
