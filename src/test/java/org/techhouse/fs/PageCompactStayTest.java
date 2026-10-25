package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;

public class PageCompactStayTest {
    private static final String COLL = "compactColl";
    private static final long SMALL_PAGE = 4096;
    private static final long APPENDED = 10;

    @TempDir
    File tmp;

    private final Configuration configuration = Configuration.getInstance();
    private long maxPageSize;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        maxPageSize = configuration.getMaxPageSize();
        TestUtils.setPrivateField(configuration, "maxPageSize", SMALL_PAGE);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", maxPageSize);
        TestUtils.standardTearDown();
    }

    private static ReplacerFixture fixture(File root, String dbName) throws Exception {
        final var rf = new ReplacerFixture(new File(root, dbName), dbName, COLL);
        rf.fx.seed(0, "a", "b");
        return rf;
    }

    private static void appendRaw(ReplacerFixture rf, String text) throws Exception {
        Files.writeString(rf.fx.page(0).toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static String hole(int length) {
        return " ".repeat(length - 1) + "\n";
    }

    private static VacuumedPage stay(ReplacerFixture rf) throws Exception {
        return rf.vacuum.vacuumIfNeeded(rf.fx.dbName, COLL, 0, APPENDED, null, rf.fx.indexedRows());
    }

    @Test
    public void test_an_admin_stay_write_compacts_below_the_cap() throws Exception {
        for (final var dbName : new String[]{Globals.ADMIN_DB_NAME, Globals.ADMIN_PAGES_DB_NAME}) {
            final var rf = fixture(tmp, dbName);
            appendRaw(rf, hole(2000));

            assertNotNull(stay(rf), dbName);

            assertEquals(StorageFixture.record("a", "old-a") + StorageFixture.record("b", "old-b"),
                    rf.fx.firstPageText(), dbName);
        }
    }

    @Test
    public void test_an_admin_stay_write_below_the_floor_appends() throws Exception {
        final var rf = fixture(tmp, Globals.ADMIN_DB_NAME);
        appendRaw(rf, hole(400));

        assertNull(stay(rf));
    }

    @Test
    public void test_a_user_stay_write_does_not_compact_below_the_cap() throws Exception {
        final var rf = fixture(tmp, "userDb");
        appendRaw(rf, hole(2000));
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertNull(stay(rf));

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
    }

    @Test
    public void test_a_refused_compaction_below_the_cap_appends_and_leaves_no_trace() throws Exception {
        final var rf = fixture(tmp, Globals.ADMIN_DB_NAME);
        appendRaw(rf, hole(2000) + StorageFixture.record("stranger", "x"));
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertNull(stay(rf));

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, rf.fx.openIntents());
        assertFalse(rf.fx.paths.vacuumFile(Globals.ADMIN_DB_NAME, COLL, 0).exists());
    }

    @Test
    public void test_a_refusal_at_the_cap_still_fails() throws Exception {
        final var rf = fixture(tmp, Globals.ADMIN_DB_NAME);
        appendRaw(rf, hole(5000) + StorageFixture.record("stranger", "x"));

        assertThrows(VacuumRefusedException.class, () -> stay(rf));
    }
}
