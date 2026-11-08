package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class WriteAheadLogForceFailureTest {
    private static final String DB = "forceFailDb";
    private static final String COLL = "forceFailColl";

    private final FaultInjector injector = IocContainer.get(FaultInjector.class);

    @TempDir
    File tmp;

    private PowerLossFixture power;
    private ReplacerFixture rf;
    private WriteAheadLog wal;

    @BeforeEach
    public void setUp() throws Exception {
        power = PowerLossFixture.install();
        rf = new ReplacerFixture(tmp, DB, COLL);
        for (final var id : List.of("a", "b", "c")) {
            rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
        }
        wal = new WriteAheadLog(rf.fx.paths);
        wal.checkpoint(DB, COLL);
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        ForceFailures.forgetUnder(tmp);
        power.close();
    }

    private void failNextForceOf(File file) {
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, file.getName());
    }

    private Map<String, String> afterPowerLossAndRedo() throws IOException {
        power.powerLoss();
        PowerLossFixture.restart(rf.fx.paths, DB, COLL);
        ForceFailures.forgetUnder(tmp);
        WalRedo.redoAll(rf.fx.paths);
        final var values = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(DB, COLL)) {
            entries.forEach(entry -> values.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        assertTrue(rf.fx.everyRowReadsItsOwnRecord(), "every pk row points at its own record");
        return values;
    }

    private static Map<String, String> withNewB() {
        return Map.of("a", "old-a", "b", "new-b", "c", "old-c");
    }

    private void aFailedCheckpointForceOf(File file) throws Exception {
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        failNextForceOf(file);

        assertThrows(IOException.class, () -> wal.checkpoint(DB, COLL));
        wal.checkpoint(DB, COLL);

        assertEquals(withNewB(), afterPowerLossAndRedo());
    }

    @Test
    public void aFailedCheckpointForceOfAPageIsNeverRetriedAndTrusted() throws Exception {
        aFailedCheckpointForceOf(rf.fx.page(0));
    }

    @Test
    public void aFailedCheckpointForceOfThePkIndexIsNeverRetriedAndTrusted() throws Exception {
        aFailedCheckpointForceOf(rf.fx.paths.pkIndexFile(DB, COLL));
    }

    @Test
    public void theRebuiltFileIsANewFileHoldingEveryLoggedByte() throws Exception {
        final var page = rf.fx.page(0);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        final var bytes = Files.readAllBytes(page.toPath());
        final var inode = Files.readAttributes(page.toPath(), BasicFileAttributes.class).fileKey();
        failNextForceOf(page);
        assertThrows(IOException.class, () -> wal.checkpoint(DB, COLL));
        assertTrue(ForceFailures.hasFailed(page.toPath()));

        wal.checkpoint(DB, COLL);

        assertNotEquals(inode, Files.readAttributes(page.toPath(), BasicFileAttributes.class).fileKey());
        assertArrayEquals(bytes, Files.readAllBytes(page.toPath()));
        assertFalse(ForceFailures.hasFailed(page.toPath()));
        assertFalse(
                rf.fx.paths.collectionFolder(DB, COLL).toPath().resolve(page.getName() + ".repair").toFile().exists());
    }

    @Test
    public void aFailedRebuildKeepsTheLogRecords() throws Exception {
        final var page = rf.fx.page(0);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        failNextForceOf(page);
        assertThrows(IOException.class, () -> wal.checkpoint(DB, COLL));
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, page.getName() + ".repair");

        assertThrows(IOException.class, () -> wal.checkpoint(DB, COLL));

        assertTrue(ForceFailures.hasFailed(page.toPath()));
        assertEquals(withNewB(), afterPowerLossAndRedo());
    }

    @Test
    public void aFailedForceOfAFileThatIsGoneIsForgotten() throws Exception {
        final var gone = rf.fx.page(7).toPath();
        ForceFailures.failed(gone);

        wal.checkpoint(DB, COLL);

        assertFalse(ForceFailures.hasFailed(gone));
    }

    @Test
    public void aFailedFieldIndexLogForceIsRebuiltFromItsLiveCopy() throws Exception {
        final var indexLog = rf.fx.paths.collectionFolder(DB, COLL).toPath().resolve(COLL + "-v-String.idxlog");
        Files.writeString(indexLog, "logged index changes\n");
        final var inode = Files.readAttributes(indexLog, BasicFileAttributes.class).fileKey();
        ForceFailures.failed(indexLog);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        wal.checkpoint(DB, COLL);

        assertNotEquals(inode, Files.readAttributes(indexLog, BasicFileAttributes.class).fileKey());
        assertEquals("logged index changes\n", Files.readString(indexLog));
        assertFalse(ForceFailures.hasFailed(indexLog));
    }

    @Test
    public void aWriteAfterAFailedLogForceIsNeverForcedIntoTheSameLog() throws Exception {
        failNextForceOf(rf.fx.paths.writeAheadLog(DB, COLL));
        assertThrows(IOException.class, () -> rf.replace("b", "new-b", 0, PagePlacement.APPEND));

        rf.replace("c", "new-c", 0, PagePlacement.APPEND);

        assertEquals(Map.of("a", "old-a", "b", "old-b", "c", "new-c"), afterPowerLossAndRedo());
    }
}
