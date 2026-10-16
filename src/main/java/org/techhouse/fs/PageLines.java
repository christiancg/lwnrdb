package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

final class PageLines {
    private static final EJson eJson = IocContainer.get(EJson.class);

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
        final var lines = new ArrayList<Line>();
        var start = 0;
        while (start < bytes.length) {
            final var end = FileLocks.lineEnd(bytes, start);
            final var next = FileLocks.nextLineStart(bytes, end);
            if (!PageHoles.isSkippable(bytes, start, end)) {
                lines.add(new Line(baseOffset + start, next - start, idOf(bytes, start, end)));
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

    static String idAt(File page, long position, long length) throws IOException {
        if (!page.exists() || position < 0 || length <= 0 || position + length > page.length()) {
            return null;
        }
        try (var reader = new RandomAccessFile(page, Globals.R_PERMISSIONS)) {
            final var bytes = new byte[Math.toIntExact(length)];
            reader.seek(position);
            reader.readFully(bytes);
            if (PageHoles.isSkippable(bytes, 0, FileLocks.lineEnd(bytes, 0))) {
                return null;
            }
            return idOf(bytes, 0, FileLocks.lineEnd(bytes, 0));
        }
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
