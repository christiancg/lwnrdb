package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

public class PageLinesEdgeIdTest {

    private static String edgeId(String line) {
        final var bytes = line.getBytes(StandardCharsets.UTF_8);
        return PageLines.edgeIdOf(bytes, 0, bytes.length);
    }

    private static String parsedId(String line) {
        final var bytes = line.getBytes(StandardCharsets.UTF_8);
        return PageLines.idOf(bytes, 0, bytes.length);
    }

    private static void assertSameAsTheParse(String line) {
        assertEquals(parsedId(line), edgeId(line), line);
    }

    @Test
    public void aLeadingIdIsReadWithoutParsing() {
        assertEquals("d-1_x", edgeId("{\"_id\":\"d-1_x\",\"a\":1}"));
        assertEquals("only", edgeId("{\"_id\":\"only\"}"));
    }

    @Test
    public void aTrailingIdIsReadWithoutParsing() {
        assertEquals("d42", edgeId("{\"a\":1,\"b\":\"x\",\"_id\":\"d42\"}"));
    }

    @Test
    public void everyShapeAgreesWithTheFullParse() {
        for (final var line : List.of("{\"_id\":\"a\",\"x\":{\"_id\":\"inner\"}}",
                "{\"x\":{\"_id\":\"inner\"},\"_id\":\"outer\"}", "{\"x\":{\"y\":1,\"_id\":\"inner\"}}",
                "{\"x\":\"q\\\",\\\"_id\\\":\\\"fake\",\"y\":2}", "{\"x\":\"tail\\\",\\\"_id\\\":\\\"fake\"}",
                "{\"_id\":42,\"a\":1}", "{\"a\":1,\"_id\":42}", "{\"_id\":\"\",\"a\":1}", "{\"a\":1,\"_id\":\"\"}",
                "{\"_id\":\"has space\",\"a\":1}", "{\"a\":1,\"_id\":\"has.dot\"}", "{\"a\":1}", "{}",
                "{\"_id\":\"cut\"", "{\"a\":1,\"_id\":\"x\"} ")) {
            assertSameAsTheParse(line);
        }
    }

    @Test
    public void aLineThatIsNotJsonFallsBackToNoId() {
        assertNull(edgeId("not json"));
        assertNull(edgeId(""));
    }

    @Test
    public void verifiedRecordsCarryTheSameIdsAsParsedRecords() {
        final var page = """
                {"_id":"a","v":1}
                     \s
                {"v":2,"_id":"b"}
                {"v":{"_id":"n"},"w":3,"_id":"c"}
                """.getBytes(StandardCharsets.UTF_8);

        assertEquals(PageLines.recordsOf(page, 10L), PageLines.verifiedRecordsOf(page, 10L));
        assertEquals(List.of("a", "b", "c"),
                PageLines.verifiedRecordsOf(page, 0L).stream().map(PageLines.Line::id).toList());
    }
}
