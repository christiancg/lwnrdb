package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class AdminEpochTest {
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_bump_increments_and_persists() throws Exception {
        assertEquals(1L, adminEpoch.bump());
        assertEquals(2L, adminEpoch.bump());
        assertEquals(2L, adminEpoch.current());
        final var persisted = Files.readString(epochPath(), StandardCharsets.UTF_8).trim();
        assertEquals("2", persisted);
    }

    @Test
    public void test_adopt_only_advances_on_higher_value() {
        adminEpoch.adopt(5L);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(3L);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(5L);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(7L);
        assertEquals(7L, adminEpoch.current());
    }

    @Test
    public void test_load_reads_persisted_value() throws Exception {
        Files.createDirectories(epochPath().getParent());
        Files.writeString(epochPath(), "42", StandardCharsets.UTF_8);
        adminEpoch.load();
        assertEquals(42L, adminEpoch.current());
    }

    @Test
    public void test_load_defaults_to_zero_when_absent() {
        adminEpoch.load();
        assertEquals(0L, adminEpoch.current());
    }

    @Test
    public void test_load_ignores_malformed_content() throws Exception {
        Files.createDirectories(epochPath().getParent());
        Files.writeString(epochPath(), "not-a-number", StandardCharsets.UTF_8);
        adminEpoch.load();
        assertEquals(0L, adminEpoch.current());
    }

    @Test
    public void test_bump_survives_unwritable_path() throws Exception {
        // Point filePath at a regular file so creating the cluster directory (and persisting) fails; the
        // in-memory epoch still advances (best-effort persistence).
        final var config = Configuration.getInstance();
        final var original = config.getFilePath();
        final var blocker = Paths.get(original, "blocker-file");
        Files.createDirectories(Paths.get(original));
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        try {
            TestUtils.setPrivateField(config, "filePath", blocker.toString());
            assertEquals(1L, adminEpoch.bump());
        } finally {
            TestUtils.setPrivateField(config, "filePath", original);
        }
    }

    private static java.nio.file.Path epochPath() {
        return Paths.get(Configuration.getInstance().getFilePath(), Globals.CLUSTER_FOLDER,
                Globals.CLUSTER_ADMIN_EPOCH_FILE);
    }
}
