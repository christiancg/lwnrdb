package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.ex.DirectoryNotFoundException;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.test.TestUtils;

public class FileSystemCreateBasePathTest {
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

    @Test
    public void test_base_path_is_created_owner_only(@TempDir Path tmp) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        final var base = tmp.resolve("nested").resolve("db");
        TestUtils.setPrivateField(config, "filePath", base.toString());

        new FileSystem().createBaseDbPath();

        assertTrue(Files.isDirectory(base));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(base)));
    }

    @Test
    public void test_database_and_collection_directories_are_owner_only(@TempDir Path tmp) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        final var base = tmp.resolve("db");
        TestUtils.setPrivateField(config, "filePath", base.toString());
        final var fs = new FileSystem();
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        assertTrue(fs.createDatabaseFolder("ownerdb"));
        assertTrue(fs.createCollectionFile("ownerdb", "ownercoll"));
        fs.metadataFiles().writeProcedure("ownerdb", "ownerproc", "{}");

        for (final var folder : new Path[]{base.resolve("admin"), base.resolve("admin").resolve("pages"),
                base.resolve("ownerdb"), base.resolve("ownerdb").resolve("ownercoll"),
                base.resolve("ownerdb").resolve(".procedures")}) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)),
                    folder.toString());
        }
        assertTrue(fs.createDatabaseFolder("ownerdb"));
    }

    @Test
    public void test_a_base_path_that_cannot_be_created_is_reported(@TempDir Path tmp) throws Exception {
        final var blocker = Files.writeString(tmp.resolve("blocker"), "x");
        TestUtils.setPrivateField(config, "filePath", blocker.resolve("db").toString());

        assertThrows(DirectoryNotFoundException.class, () -> new FileSystem().createBaseDbPath());
    }
}
