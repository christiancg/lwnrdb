package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.fs.CompactionJournal.Kind;
import org.techhouse.fs.CompactionJournal.Marker;

public class StreamedCompactionJournalTest {
    private static final String DB = "streamDb";
    private static final String COLL = "streamColl";
    private static final int CHUNK_CROSSING_BYTES = 200_000;

    @TempDir
    File tmp;

    private CompactionFixture fx;

    @BeforeEach
    public void setUp() throws IOException {
        fx = new CompactionFixture(new File(tmp, "main"), DB, COLL);
        fx.seed(0, "a");
    }

    private byte[] writePage(Random random, int length) throws IOException {
        final var content = new byte[length];
        random.nextBytes(content);
        Files.write(fx.page(0).toPath(), content);
        return content;
    }

    private static Marker withTail(Marker marker, byte[] tail) {
        return new Marker(marker.kind(), marker.dbName(), marker.collName(), marker.page(), marker.position(),
                marker.originalLength(), marker.removedLength(), marker.id(), marker.version(), marker.targetPage(),
                marker.targetLengthBefore(), tail);
    }

    private Marker beginStreamed(Kind kind, PkIndexEntry removed, long length) throws IOException {
        try (var page = FileChannel.open(fx.page(0).toPath(), StandardOpenOption.READ)) {
            return fx.journal.begin(kind, removed, length, page);
        }
    }

    @Test
    public void aStreamedMarkerHasTheBytesOfABufferedOne() throws IOException {
        final var random = new Random(14L);
        for (var round = 0; round < 50; round++) {
            assertStreamedMatchesBuffered(random, round);
        }
    }

    private void assertStreamedMatchesBuffered(Random random, int round) throws IOException {
        final var content = writePage(random, 1 + random.nextInt(CHUNK_CROSSING_BYTES));
        final var position = random.nextInt(content.length);
        final var removed = new PkIndexEntry(DB, COLL, "id" + round, position,
                1 + random.nextInt(content.length - position), 0, random.nextLong());

        final var marker = beginStreamed(Kind.values()[round % Kind.values().length], removed, content.length);

        final var tail = Arrays.copyOfRange(content, position, content.length);
        assertArrayEquals(LegacyStorageOracle.encodeMarker(withTail(marker, tail)),
                Files.readAllBytes(fx.journal.markerFile(marker).toPath()));
        assertArrayEquals(tail, fx.journal.tailOf(marker));
        fx.journal.end(marker);
    }

    @Test
    public void retargetingAStreamedMarkerKeepsItsTail() throws IOException {
        final var content = writePage(new Random(3L), CHUNK_CROSSING_BYTES);
        final var removed = new PkIndexEntry(DB, COLL, "moved", 10, 50, 0, 7L);
        final var marker = beginStreamed(Kind.RELOCATE, removed, content.length);

        final var retargeted = fx.journal.retarget(marker, 4, 1234L);

        final var tail = Arrays.copyOfRange(content, 10, content.length);
        assertArrayEquals(LegacyStorageOracle.encodeMarker(withTail(retargeted, tail)),
                Files.readAllBytes(fx.journal.markerFile(retargeted).toPath()));
        final var decoded = CompactionJournal.decode(Files.readAllBytes(fx.journal.markerFile(retargeted).toPath()));
        assertNotNull(decoded);
        assertArrayEquals(tail, decoded.tail());
    }

    @Test
    public void aPageShorterThanTheJournaledRegionIsRefused() throws IOException {
        writePage(new Random(5L), 100);
        final var removed = new PkIndexEntry(DB, COLL, "short", 10, 20, 0, 1L);

        assertThrows(IOException.class, () -> beginStreamed(Kind.UPDATE, removed, 500));
    }

    @Test
    public void aTornMarkerCannotBeReadBack() throws IOException {
        writePage(new Random(6L), 100);
        final var removed = new PkIndexEntry(DB, COLL, "torn", 10, 20, 0, 1L);
        final var marker = beginStreamed(Kind.UPDATE, removed, 100);
        Files.write(fx.journal.markerFile(marker).toPath(), new byte[]{'U', 'P'});

        assertThrows(IOException.class, () -> fx.journal.tailOf(marker));
    }

    @Test
    public void shiftingATailLongerThanOneChunkMovesEveryByte() throws IOException {
        final var content = writePage(new Random(8L), CHUNK_CROSSING_BYTES);
        final var removed = new PkIndexEntry(DB, COLL, "first", 1000, 3000, 0, 1L);

        try (var page = FileChannel.open(fx.page(0).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertTrue(PageCompactor.shiftTailOver(page, removed, content.length));
            page.truncate(content.length - removed.getLength());
        }

        final var expected = new byte[content.length - 3000];
        System.arraycopy(content, 0, expected, 0, 1000);
        System.arraycopy(content, 4000, expected, 1000, content.length - 4000);
        assertArrayEquals(expected, Files.readAllBytes(fx.page(0).toPath()));
    }

    @Test
    public void theLastRecordNeedsNoShift() throws IOException {
        final var content = writePage(new Random(9L), 500);
        final var removed = new PkIndexEntry(DB, COLL, "last", 400, 100, 0, 1L);

        try (var page = FileChannel.open(fx.page(0).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            assertFalse(PageCompactor.shiftTailOver(page, removed, content.length));
        }
    }
}
