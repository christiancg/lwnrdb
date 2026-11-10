package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class PageVacuumCheckpointTest {
    private static final String DB = "rewriteCkptDb";
    private static final String COLL = "rewriteCkptColl";
    private static final long WAIT_MS = 10_000L;

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
        wal = new WriteAheadLog(rf.fx.paths);
        for (final var id : List.of("a", "b", "c")) {
            rf.appender.insert(rf.entry(id, "old-" + id, 0), ReplacerFixture.line(id, "old-" + id));
        }
        wal.checkpoint(DB, COLL);
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        power.close();
    }

    private WalCodec.Decoded decodedLog() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(rf.fx.paths.writeAheadLog(DB, COLL).toPath()));
    }

    private Map<String, String> scannedAfterPowerLoss() throws IOException {
        power.powerLoss();
        PowerLossFixture.restart(rf.fx.paths, DB, COLL);
        WalRedo.redoAll(rf.fx.paths);
        final var scanned = new TreeMap<String, String>();
        try (var entries = new DocumentPageStore(rf.fx.paths).streamEntries(DB, COLL)) {
            entries.forEach(entry -> scanned.merge(entry.get_id(), entry.getData().get("v").asJsonString().getValue(),
                    (first, second) -> first + "," + second));
        }
        return scanned;
    }

    @Test
    public void aCheckpointDuringARewriteKeepsTheBegin() throws Exception {
        rf.replacer.delete(rf.fx.indexed("a"));
        injector.arm(FaultPoint.WAL_REWRITE_BEGUN, FaultAction.PAUSE, DB + Globals.COLL_IDENTIFIER_SEPARATOR + COLL);
        final var vacuum = CompletableFuture.runAsync(() -> {
            try {
                rf.vacuum.vacuum(DB, COLL, 0, PageRows.of(rf.fx.store, null, DB, COLL, 0));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, command -> Thread.ofVirtual().start(command));
        assertTrue(injector.awaitReached(FaultPoint.WAL_REWRITE_BEGUN, WAIT_MS), "the vacuum never began");

        assertThrows(RewriteOpenException.class, () -> wal.checkpoint(DB, COLL));
        injector.release(FaultPoint.WAL_REWRITE_BEGUN);
        vacuum.get(WAIT_MS, TimeUnit.MILLISECONDS);

        final var rewrite = decodedLog().rewrites().get(0L);
        assertNotNull(rewrite, "the log still bounds the page by its REWRITE_BEGIN");
        assertNotEquals(WalCodec.NO_REWRITE_END, rewrite.lengthAfter());
        wal.checkpoint(DB, COLL);
        assertEquals(Map.of("b", "old-b", "c", "old-c"), scannedAfterPowerLoss());
    }
}
