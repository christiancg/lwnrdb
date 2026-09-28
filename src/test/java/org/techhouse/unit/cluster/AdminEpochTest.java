package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
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
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(adminEpoch, "unreadable", false);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        TestUtils.setPrivateField(adminEpoch, "confirmed", true);
        TestUtils.setPrivateField(adminEpoch, "unreadable", false);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_bump_increments_and_persists() throws Exception {
        assertEquals(1L, adminEpoch.bump());
        assertEquals(2L, adminEpoch.bump());
        assertEquals(2L, adminEpoch.current());
        final var persisted = Files.readString(epochPath(), StandardCharsets.UTF_8).trim();
        assertEquals("2|false", persisted);
    }

    @Test
    public void test_adopt_only_advances_on_higher_value() {
        adminEpoch.adopt(5L, true);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(3L, true);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(5L, true);
        assertEquals(5L, adminEpoch.current());
        adminEpoch.adopt(7L, true);
        assertEquals(7L, adminEpoch.current());
    }

    @Test
    public void test_load_reads_persisted_value() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
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
    public void test_load_flags_malformed_content_as_unreadable() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "not-a-number", StandardCharsets.UTF_8);

        adminEpoch.load();

        assertEquals(0L, adminEpoch.current());
        assertTrue(adminEpoch.isUnreadable(),
                "a file that exists but cannot be parsed is not an absent one: bidding 0 with real data on disk"
                        + " loses every comparison and unregisters it");
    }

    @Test
    public void test_load_flags_a_torn_file_as_unreadable() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "", StandardCharsets.UTF_8);

        adminEpoch.load();

        assertTrue(adminEpoch.isUnreadable(),
                "an empty file is the shape a crash mid-write leaves, and it used to load as a silent zero");
    }

    @Test
    public void test_an_absent_file_is_a_genuinely_new_node() {
        adminEpoch.load();

        assertEquals(0L, adminEpoch.current());
        assertFalse(adminEpoch.isUnreadable(), "a node that never committed an admin op starts at 0 legitimately");
    }

    @Test
    public void test_a_persisted_epoch_clears_the_unreadable_flag() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "garbage", StandardCharsets.UTF_8);
        adminEpoch.load();
        assertTrue(adminEpoch.isUnreadable());

        adminEpoch.bump();

        assertFalse(adminEpoch.isUnreadable(), "once it writes a good value the node has authority again");
        assertEquals("1|false", Files.readString(epochPath(), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void test_a_bare_number_file_loads_as_confirmed() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "42", StandardCharsets.UTF_8);

        adminEpoch.load();

        assertEquals(42L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed(),
                "an epoch file written before the flag existed must keep the behaviour it had, or every"
                        + " upgraded node would silently lose an epoch tie it used to win");
    }

    @Test
    public void test_the_confirmed_flag_round_trips_through_persist_and_load() throws Exception {
        adminEpoch.bump();
        assertFalse(adminEpoch.isConfirmed(), "a bump has not reached quorum until the replication says so");
        adminEpoch.confirm();
        assertEquals("1|true", Files.readString(epochPath(), StandardCharsets.UTF_8).trim());

        adminEpoch.load();

        assertEquals(1L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed());
    }

    @Test
    public void test_mark_unconfirmed_persists_the_cleared_flag() throws Exception {
        adminEpoch.bump();
        adminEpoch.confirm();

        adminEpoch.markUnconfirmed();

        assertFalse(adminEpoch.isConfirmed());
        assertEquals("1|false", Files.readString(epochPath(), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void test_an_unrecognised_confirmation_flag_is_unreadable() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "7|maybe", StandardCharsets.UTF_8);

        adminEpoch.load();

        assertEquals(0L, adminEpoch.current());
        assertTrue(adminEpoch.isUnreadable(),
                "a flag that is neither true nor false is a torn file, not a node that lost its quorum");
    }

    @Test
    public void test_adopt_takes_the_confirmation_of_the_epoch_it_follows() {
        adminEpoch.adopt(5L, false);
        assertEquals(5L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed(),
                "conforming to an unconfirmed snapshot must not launder it into a confirmed one");

        adminEpoch.adopt(6L, true);
        assertTrue(adminEpoch.isConfirmed());

        adminEpoch.adopt(4L, false);
        assertTrue(adminEpoch.isConfirmed(), "a lower candidate changes nothing at all");
    }

    @Test
    public void test_persist_leaves_no_temp_file_behind() {
        adminEpoch.bump();

        final var tmp = epochPath().resolveSibling(epochPath().getFileName() + ".tmp");
        assertFalse(Files.exists(tmp), "the atomic write must move its temp file into place, not leave it");
    }

    @Test
    public void test_bump_survives_unwritable_path() throws Exception {
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
    @Test
    public void test_an_equal_epoch_promotes_an_unconfirmed_node_to_confirmed() {
        adminEpoch.bump();
        assertFalse(adminEpoch.isConfirmed());

        adminEpoch.adopt(1L, true);

        assertEquals(1L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed(),
                "a node that conformed to a confirmed snapshot at its own epoch has that snapshot's content");
    }

    @Test
    public void test_an_equal_epoch_never_demotes_a_confirmed_node() {
        adminEpoch.bump();
        adminEpoch.confirm();

        adminEpoch.adopt(1L, false);

        assertTrue(adminEpoch.isConfirmed(), "a coordinator that counted a majority does not lose that evidence");
    }

    @Test
    public void test_an_equal_unconfirmed_epoch_changes_nothing() {
        adminEpoch.bump();

        adminEpoch.adopt(1L, false);

        assertEquals(1L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_higher_epoch_adopts_its_own_confirmation_either_way() {
        adminEpoch.adopt(5L, false);
        assertEquals(5L, adminEpoch.current());
        assertFalse(adminEpoch.isConfirmed());

        adminEpoch.adopt(6L, true);

        assertEquals(6L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed());
    }

    @Test
    public void test_a_bare_number_on_disk_still_loads_as_confirmed() throws Exception {
        Files.createDirectories(Objects.requireNonNull(epochPath().getParent()));
        Files.writeString(epochPath(), "9", StandardCharsets.UTF_8);

        adminEpoch.load();

        assertEquals(9L, adminEpoch.current());
        assertTrue(adminEpoch.isConfirmed(), "an existing deployment must behave exactly as it did before");
    }
}
