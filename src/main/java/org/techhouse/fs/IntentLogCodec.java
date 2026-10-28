package org.techhouse.fs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;

final class IntentLogCodec {
    private static final String HEADER = "INTENT";
    private static final String END = "END";
    private static final String DONE = "DONE";
    private static final String VACUUM = "VACUUM";
    private static final char LINE_END = '\n';
    private static final int HEADER_FIELDS = 5;
    private static final int DONE_FIELDS = 2;
    private static final int VACUUM_FIELDS = 2;
    private static final int LINE_BYTES_HINT = 96;

    record Parsed(List<WriteIntentJournal.Intent> open, long lastSeq, long cleanLength) {
    }

    private IntentLogCodec() {
    }

    static byte[] encode(WriteIntentJournal.Intent intent) {
        final var text = new StringBuilder(LINE_BYTES_HINT * (intent.items().size() + 2)).append(HEADER);
        field(text, intent.seq());
        field(text, token(intent.dbName()));
        field(text, token(intent.collName()));
        field(text, intent.items().size()).append(LINE_END);
        for (final var item : intent.items()) {
            switch (item) {
                case WriteIntent.Vacuum vacuum -> field(text.append(VACUUM), vacuum.page());
            }
            text.append(LINE_END);
        }
        return text.append(END).append(LINE_END).toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeDone(long seq) {
        return field(new StringBuilder(DONE), seq).append(LINE_END).toString().getBytes(StandardCharsets.UTF_8);
    }

    static Parsed parse(File log, byte[] bytes) {
        final var lines = new String(bytes, StandardCharsets.UTF_8).split(String.valueOf(LINE_END), -1);
        final var open = new LinkedHashMap<Long, WriteIntentJournal.Intent>();
        var lastSeq = 0L;
        var cleanLength = 0L;
        var index = 0;
        while (index < lines.length - 1) {
            final var fields = fields(lines[index]);
            final long seq;
            final int next;
            if (DONE.equals(fields[0]) && fields.length == DONE_FIELDS) {
                seq = parseSeq(fields[1]);
                if (seq < 0) {
                    break;
                }
                open.remove(seq);
                next = index + 1;
            } else {
                final var block = decodeBlock(log, lines, index);
                if (block == null) {
                    break;
                }
                seq = block.seq();
                open.put(seq, block);
                next = index + block.items().size() + 2;
            }
            lastSeq = Math.max(lastSeq, seq);
            for (var line = index; line < next; line++) {
                cleanLength += lines[line].getBytes(StandardCharsets.UTF_8).length + 1;
            }
            index = next;
        }
        return new Parsed(List.copyOf(open.values()), lastSeq, cleanLength);
    }

    private static WriteIntentJournal.Intent decodeBlock(File log, String[] lines, int start) {
        try {
            final var header = fields(lines[start]);
            if (header.length != HEADER_FIELDS || !HEADER.equals(header[0])) {
                return null;
            }
            final var count = Integer.parseInt(header[4]);
            final var end = start + count + 1;
            if (count < 0 || end >= lines.length - 1 || !END.equals(lines[end])) {
                return null;
            }
            final var items = new ArrayList<WriteIntent>(count);
            for (var index = start + 1; index < end; index++) {
                final var item = decodeItem(fields(lines[index]));
                if (item == null) {
                    return null;
                }
                items.add(item);
            }
            return new WriteIntentJournal.Intent(log, Long.parseLong(header[1]),
                    FieldIndexEntry.unescapeIndexToken(header[2]), FieldIndexEntry.unescapeIndexToken(header[3]),
                    List.copyOf(items));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long parseSeq(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static WriteIntent decodeItem(String[] fields) {
        return VACUUM.equals(fields[0]) && fields.length == VACUUM_FIELDS
                ? new WriteIntent.Vacuum(Long.parseLong(fields[1]))
                : null;
    }

    private static String[] fields(String line) {
        return line.split(Globals.ID_SEPARATOR, -1);
    }

    private static void field(StringBuilder text, String value) {
        text.append(Globals.ID_SEPARATOR).append(value);
    }

    private static StringBuilder field(StringBuilder text, long value) {
        return text.append(Globals.ID_SEPARATOR).append(value);
    }

    private static String token(String value) {
        return FieldIndexEntry.escapeIndexToken(value);
    }
}
