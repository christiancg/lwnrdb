package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

final class PageLines {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final byte[] ID_KEY = ("{\"" + Globals.PK_FIELD + "\":\"").getBytes(StandardCharsets.US_ASCII);

    private PageLines() {
    }

    record Line(long position, int length, String id) {
    }

    static List<Line> recordsOf(File page, long fromOffset) throws IOException {
        if (!page.exists() || page.length() <= fromOffset) {
            return List.of();
        }
        return recordsOf(readFrom(page, fromOffset), fromOffset);
    }

    static List<Line> recordsOf(byte[] bytes, long baseOffset) {
        return recordsOf(bytes, baseOffset, false);
    }

    static List<Line> verifiedRecordsOf(byte[] bytes, long baseOffset) {
        return recordsOf(bytes, baseOffset, true);
    }

    private static List<Line> recordsOf(byte[] bytes, long baseOffset, boolean idsAtTheEdges) {
        final var lines = new ArrayList<Line>();
        var start = 0;
        while (start < bytes.length) {
            final var end = FileLocks.lineEnd(bytes, start);
            final var next = FileLocks.nextLineStart(bytes, end);
            if (!PageHoles.isSkippable(bytes, start, end)) {
                lines.add(new Line(baseOffset + start, next - start,
                        idsAtTheEdges ? edgeIdOf(bytes, start, end) : idOf(bytes, start, end)));
            }
            start = next;
        }
        return lines;
    }

    static String idOf(byte[] bytes, int start, int end) {
        try {
            final var id = eJson.fromJson(bytes, start, end, JsonObject.class).get(Globals.PK_FIELD);
            return id != null && id.isJsonString() ? id.asJsonString().getValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String edgeIdOf(byte[] bytes, int start, int end) {
        final var leading = leadingIdEnd(bytes, start, end);
        if (leading > 0) {
            return new String(bytes, start + ID_KEY.length, leading - start - ID_KEY.length, StandardCharsets.US_ASCII);
        }
        final var trailing = trailingIdStart(bytes, start, end);
        if (trailing > 0) {
            return new String(bytes, trailing, end - 2 - trailing, StandardCharsets.US_ASCII);
        }
        return idOf(bytes, start, end);
    }

    private static int leadingIdEnd(byte[] bytes, int start, int end) {
        final var from = start + ID_KEY.length;
        if (end - from < 3 || !Arrays.equals(bytes, start, from, ID_KEY, 0, ID_KEY.length)) {
            return -1;
        }
        var at = from;
        while (at < end && isIdByte(bytes[at])) {
            at++;
        }
        return at > from && at + 1 < end && bytes[at] == '"' && (bytes[at + 1] == ',' || bytes[at + 1] == '}')
                ? at
                : -1;
    }

    private static int trailingIdStart(byte[] bytes, int start, int end) {
        if (end - start < ID_KEY.length + 2 || bytes[end - 1] != '}' || bytes[end - 2] != '"') {
            return -1;
        }
        var at = end - 3;
        while (at > start && isIdByte(bytes[at])) {
            at--;
        }
        final var keyStart = at - (ID_KEY.length - 2);
        if (at == end - 3 || keyStart <= start || !Arrays.equals(bytes, keyStart, at + 1, ID_KEY, 1, ID_KEY.length)
                || bytes[keyStart - 1] != ',' && bytes[keyStart - 1] != '{') {
            return -1;
        }
        return at + 1;
    }

    private static boolean isIdByte(byte b) {
        return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '_' || b == '-';
    }

    static byte[] readFrom(File page, long offset) throws IOException {
        try (var reader = new RandomAccessFile(page, Globals.R_PERMISSIONS)) {
            final var bytes = new byte[Math.toIntExact(reader.length() - offset)];
            reader.seek(offset);
            reader.readFully(bytes);
            return bytes;
        }
    }
}
