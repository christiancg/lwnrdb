package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.StandaloneEpochSeed;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.test.TestUtils;

public class StandaloneEpochSeedTest {
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Configuration config = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        resetEpoch();
    }

    @AfterEach
    public void tearDown() throws Exception {
        resetEpoch();
        TestUtils.standardTearDown();
    }

    private void resetEpoch() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(adminEpoch, "unreadable", false);
        TestUtils.setPrivateField(adminEpoch, "absent", false);
    }

    private static void saveUser(String name, String password) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(name, PasswordHasher.hash(password), true,
                new HashSet<>(), new HashMap<>(), new HashMap<>()));
    }

    private void saveBootstrapAdmin() throws Exception {
        saveUser(config.getDefaultAdminUsername(), config.getDefaultAdminPassword());
    }

    private void seedAfterLoad() {
        adminEpoch.load();
        StandaloneEpochSeed.seedIfPopulated();
    }

    @Test
    public void test_a_node_holding_a_database_is_seeded() throws Exception {
        saveBootstrapAdmin();
        TestUtils.createTestDatabaseAndCollection();

        seedAfterLoad();

        assertEquals(1L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_node_holding_only_extra_users_is_seeded() throws Exception {
        saveBootstrapAdmin();
        saveUser("analyst", "analyst-password");

        seedAfterLoad();

        assertEquals(1L, adminEpoch.current());
    }

    @Test
    public void test_a_node_whose_bootstrap_admin_password_changed_is_seeded() throws Exception {
        saveUser(config.getDefaultAdminUsername(), "a-password-nobody-configured");

        seedAfterLoad();

        assertEquals(1L, adminEpoch.current());
    }

    @Test
    public void test_a_fresh_node_with_only_the_bootstrap_admin_is_not_seeded() throws Exception {
        saveBootstrapAdmin();

        seedAfterLoad();

        assertEquals(0L, adminEpoch.current());
        assertFalse(Files.exists(epochPath()), "a fresh node must keep losing to a populated one");
    }

    @Test
    public void test_a_node_with_an_epoch_file_is_never_reseeded() throws Exception {
        saveBootstrapAdmin();
        TestUtils.createTestDatabaseAndCollection();
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "0|true", StandardCharsets.UTF_8);

        seedAfterLoad();

        assertEquals(0L, adminEpoch.current());
    }

    private Path epochPath() {
        return Paths.get(config.getFilePath(), Globals.CLUSTER_FOLDER, Globals.CLUSTER_ADMIN_EPOCH_FILE);
    }
}
