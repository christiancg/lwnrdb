package org.techhouse.listen;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.internal.EncodedJson;
import org.techhouse.ops.resp.ListenResponse;

public final class ListenPushFrames {
    private static final byte[] RESULTS_FIELD = "\"results\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMPTY_RESULTS = "\"results\":[]".getBytes(StandardCharsets.US_ASCII);

    public record Split(EncodedJson head, EncodedJson tail) {
    }

    private ListenPushFrames() {
    }

    public static Split split(UUID listenId, String hash) {
        final var frame = OutboundQueue.frameOf(new ListenResponse(listenId.toString(), List.of(), hash, true));
        final var bytes = frame.bytes();
        final var at = indexOfEmptyResults(bytes, frame.length());
        final var bodyStart = at + RESULTS_FIELD.length;
        final var tailStart = at + EMPTY_RESULTS.length;
        return new Split(EncodedJson.of(Arrays.copyOf(bytes, bodyStart)),
                EncodedJson.of(Arrays.copyOfRange(bytes, tailStart, frame.length())));
    }

    private static int indexOfEmptyResults(byte[] bytes, int length) {
        final var last = length - EMPTY_RESULTS.length;
        for (var i = 0; i <= last; i++) {
            if (Arrays.equals(bytes, i, i + EMPTY_RESULTS.length, EMPTY_RESULTS, 0, EMPTY_RESULTS.length)) {
                return i;
            }
        }
        return -1;
    }
}
