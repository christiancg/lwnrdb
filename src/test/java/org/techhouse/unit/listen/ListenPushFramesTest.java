package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.listen.ListenPushFrames;
import org.techhouse.listen.ResultHasher;
import org.techhouse.ops.resp.ListenResponse;

public class ListenPushFramesTest {
    private static JsonObject document(String id, String text) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("text", new JsonString(text));
        return object;
    }

    private static void write(ByteArrayOutputStream sink, EncodedJson segment) {
        sink.write(segment.bytes(), 0, segment.length());
    }

    private static void assertSplicesLikeTheWholeFrame(List<JsonObject> results, boolean ordered) {
        final var listenId = UUID.randomUUID();
        try (var encoded = ResultHasher.encode(results, ordered)) {
            final var split = ListenPushFrames.split(listenId, encoded.hash());
            final var spliced = new ByteArrayOutputStream();
            write(spliced, split.head());
            write(spliced, encoded.body());
            write(spliced, split.tail());

            final var whole = OutboundQueue
                    .frameOf(new ListenResponse(listenId.toString(), results, encoded.hash(), true));
            assertArrayEquals(Arrays.copyOf(whole.bytes(), whole.length()), spliced.toByteArray());
        }
    }

    @Test
    public void headBodyAndTailAreTheBytesOfTheWholeFrame() {
        assertSplicesLikeTheWholeFrame(List.of(document("a", "plain"), document("b", "two")), true);
        assertSplicesLikeTheWholeFrame(List.of(document("a", "plain")), false);
        assertSplicesLikeTheWholeFrame(List.of(), true);
    }

    @Test
    public void stringsHoldingTheMarkerEscapesAndCjkSpliceExactly() {
        assertSplicesLikeTheWholeFrame(List.of(document("m", "\"results\":[]"), document("e", "tab\t\"q\"\\ \u0001"),
                document("c", "日本語 😀 ü")), true);
        assertSplicesLikeTheWholeFrame(List.of(document("m", "\"results\":[]")), false);
    }
}
