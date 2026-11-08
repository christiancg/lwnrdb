package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DurableShadowTest {
    private static final int WRITERS = 8;
    private static final int APPENDS = 25;
    private static final int REUSE_PROBES = 64;

    @TempDir
    Path tmp;

    private Path data;
    private Path shadowRoot;
    private DurableShadow shadow;

    @BeforeEach
    public void setUp() throws IOException {
        data = Files.createDirectories(tmp.resolve("db"));
        shadowRoot = tmp.resolve("shadow");
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (shadow != null) {
            shadow.close();
        }
    }

    private void install() throws IOException {
        shadow = DurableShadow.install(data, shadowRoot);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    private void force(Path path) throws IOException {
        final var options = Files.isDirectory(path)
                ? new StandardOpenOption[]{StandardOpenOption.READ}
                : new StandardOpenOption[]{StandardOpenOption.WRITE};
        try (var channel = FileChannel.open(path, options)) {
            shadow.force(channel, path);
        }
    }

    private Map<String, String> listing(Path folder) throws IOException {
        final var names = new HashMap<String, String>();
        final var file = shadow.listingOf(folder);
        if (!Files.exists(file)) {
            return names;
        }
        for (final var line : Files.readAllLines(file)) {
            final var fields = line.split("\t");
            if (!DurableShadow.SELF.equals(fields[0])) {
                names.put(fields[0], fields[2]);
            }
        }
        return names;
    }

    private String blob(Path file) throws IOException {
        final var blob = shadow.blobOf(file);
        return Files.exists(blob) ? Files.readString(blob) : null;
    }

    @Test
    public void aFileWrittenButNeverForcedComesBackEmpty() throws IOException {
        install();
        final var page = write(data.resolve("page-0.dat"), "unforced");
        force(data);

        assertTrue(listing(data).containsKey("page-0.dat"));
        assertNull(blob(page));
    }

    @Test
    public void aNameNotInTheLastFolderForceDoesNotSurvive() throws IOException {
        install();
        force(data);
        final var page = write(data.resolve("page-1.dat"), "forced content");
        force(page);

        assertEquals("forced content", blob(page));
        assertFalse(listing(data).containsKey("page-1.dat"));
    }

    @Test
    public void aRenameIsDurableOnlyAfterTheFolderForce() throws IOException {
        install();
        final var temp = write(data.resolve("schema.json.tmp"), "{}");
        force(temp);
        force(data);
        final var moved = Files.move(temp, data.resolve("schema.json"));

        assertEquals(Map.of("schema.json.tmp", listing(data).get("schema.json.tmp")), listing(data));
        force(data);
        assertEquals(List.of("schema.json"), List.copyOf(listing(data).keySet()));
        assertEquals("{}", blob(moved));
    }

    @Test
    public void anInodeIsNotReusedWhileTheShadowPinsIt() throws IOException {
        install();
        final var pinned = write(data.resolve("pinned.dat"), "a");
        force(pinned);
        final var key = fileKeyOf(pinned);
        Files.delete(pinned);
        final var seen = new HashSet<>();
        for (var probe = 0; probe < REUSE_PROBES; probe++) {
            final var fresh = write(data.resolve("probe-" + probe + ".dat"), "b");
            seen.add(fileKeyOf(fresh));
            Files.delete(fresh);
        }

        assertFalse(seen.contains(key), "a deleted but pinned inode was handed to a new file");
    }

    @Test
    public void aKillMidRecordLeavesTheOldOrTheNewBlob() throws IOException {
        install();
        final var page = write(data.resolve("page-2.dat"), "old");
        force(page);
        final var blob = shadow.blobOf(page);
        Files.writeString(blob.resolveSibling(blob.getFileName() + ".99" + DurableShadow.TEMP_SUFFIX), "torn");

        assertEquals("old", blob(page));
        write(page, "-new");
        force(page);
        assertEquals("old-new", blob(page));
        try (Stream<Path> blobs = Files.list(blob.getParent())) {
            assertEquals(1, blobs.filter(path -> !path.toString().endsWith(DurableShadow.TEMP_SUFFIX)).count());
        }
    }

    @Test
    public void theBaselineRecordsFilesPresentAtInstall() throws IOException {
        final var folder = Files.createDirectories(data.resolve("mydb").resolve("coll"));
        final var page = write(folder.resolve("coll-1.dat"), "present");
        install();

        assertTrue(listing(data).containsKey("mydb"));
        assertTrue(listing(folder.getParent()).containsKey("coll"));
        assertEquals(listing(folder).get("coll-1.dat"), shadow.blobOf(page).getFileName().toString());
        assertEquals("present", blob(page));
    }

    @Test
    public void installWipesAnEarlierShadow() throws IOException {
        Files.createDirectories(shadowRoot.resolve("stale"));
        install();

        assertFalse(Files.exists(shadowRoot.resolve("stale")));
        assertTrue(Files.isDirectory(shadowRoot.resolve(DurableShadow.BLOBS)));
    }

    @Test
    public void aFrozenFileIgnoresLaterForces() throws IOException {
        install();
        final var log = write(data.resolve("orders.wal"), "a");
        force(log);
        shadow.forceFailed(log);
        write(log, "b");
        force(log);

        assertTrue(shadow.isFrozen(log));
        assertEquals("a", blob(log));
    }

    @Test
    public void aFrozenFolderKeepsItsLastListing() throws IOException {
        install();
        force(data);
        shadow.forceFailed(data);
        write(data.resolve("late.dat"), "x");
        force(data);

        assertEquals(Map.of(), listing(data));
    }

    @Test
    public void concurrentForcesOfOneFileKeepTheLastBlob() throws Exception {
        install();
        final var page = write(data.resolve("page-3.dat"), "");
        final var failures = new CopyOnWriteArrayList<Throwable>();
        final var appendLock = new ReentrantLock();
        final var writers = new ArrayList<Thread>();
        for (var writer = 0; writer < WRITERS; writer++) {
            writers.add(Thread.ofVirtual().start(() -> {
                try {
                    for (var append = 0; append < APPENDS; append++) {
                        appendLock.lock();
                        try {
                            write(page, "x");
                        } finally {
                            appendLock.unlock();
                        }
                        force(page);
                    }
                } catch (IOException e) {
                    failures.add(e);
                }
            }));
        }
        for (final var writer : writers) {
            writer.join();
        }

        assertEquals(List.of(), List.copyOf(failures));
        final var forced = blob(page);
        assertNotNull(forced);
        assertArrayEquals(Files.readAllBytes(page), forced.getBytes(StandardCharsets.UTF_8));
        assertEquals(WRITERS * APPENDS, forced.length());
    }

    @Test
    public void aForceOfAFileDeletedBeforeItsForceDoesNotThrow() throws IOException {
        install();
        final var page = write(data.resolve("page-4.dat"), "doomed");
        try (var channel = FileChannel.open(page, StandardOpenOption.WRITE)) {
            Files.delete(page);
            assertDoesNotThrow(() -> shadow.force(channel, page));
        }
    }

    @Test
    public void aForceOutsideTheDataRootRecordsNothing() throws IOException {
        install();
        final var outside = write(tmp.resolve("elsewhere.log"), "x");
        force(outside);

        try (Stream<Path> blobs = Files.list(shadowRoot.resolve(DurableShadow.BLOBS))) {
            assertEquals(0, blobs.count());
        }
    }

    private static Object fileKeyOf(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    }
}
