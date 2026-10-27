package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;

final class PkPointScan {
    static final int MAX_CHUNK = 1 << 20;
    private static final int MIN_CHUNK = 1 << 12;
    private static final byte SEPARATOR = (byte) Globals.ID_SEPARATOR.charAt(0);
    private static final byte LINE_FEED = '\n';
    private static final byte CARRIAGE_RETURN = '\r';

    private final String dbName;
    private final String collName;
    private final byte[][] keys;
    private final String[] ids;
    private final PkIndexEntry[] rows;
    private final int mask;
    private boolean recognised;
    private boolean malformed;

    private PkPointScan(String dbName, String collName, Collection<String> wanted) {
        this.dbName = dbName;
        this.collName = collName;
        var capacity = 2;
        while (capacity < wanted.size() * 2) {
            capacity <<= 1;
        }
        this.keys = new byte[capacity][];
        this.ids = new String[capacity];
        this.rows = new PkIndexEntry[capacity];
        this.mask = capacity - 1;
        for (final var id : wanted) {
            final var key = FieldIndexEntry.escapeIndexToken(id).getBytes(StandardCharsets.UTF_8);
            var slot = hash(key, 0, key.length) & mask;
            while (keys[slot] != null && !Arrays.equals(keys[slot], key)) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = key;
            ids[slot] = id;
        }
    }

    static Map<String, PkIndexEntry> rowsFor(InputStream in, File indexFile, String dbName, String collName,
            Collection<String> ids) throws IOException {
        final var scan = new PkPointScan(dbName, collName, ids);
        scan.read(in, indexFile.length());
        if (scan.malformed && !scan.recognised) {
            throw new IOException("No line in " + indexFile.getName() + " could be read as a PK index entry; it is"
                    + " left untouched. Rebuild it with REINDEX and \"primaryKey\": true, or repair or restore it,"
                    + " before reading " + dbName + "|" + collName);
        }
        return scan.found();
    }

    private void read(InputStream in, long length) throws IOException {
        var buffer = new byte[Math.clamp(length + 1, MIN_CHUNK, MAX_CHUNK)];
        var filled = 0;
        while (true) {
            final var read = in.read(buffer, filled, buffer.length - filled);
            if (read < 0) {
                return;
            }
            filled += read;
            final var consumed = lines(buffer, filled);
            System.arraycopy(buffer, consumed, buffer, 0, filled - consumed);
            filled -= consumed;
            if (filled == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
        }
    }

    private int lines(byte[] bytes, int filled) {
        var start = 0;
        for (var index = 0; index < filled; index++) {
            if (bytes[index] == LINE_FEED || bytes[index] == CARRIAGE_RETURN) {
                if (index > start) {
                    line(bytes, start, index);
                }
                start = index + 1;
            }
        }
        return start;
    }

    private void line(byte[] bytes, int start, int end) {
        var idEnd = start;
        while (idEnd < end && bytes[idEnd] != SEPARATOR) {
            idEnd++;
        }
        final var slot = slotOf(bytes, start, idEnd);
        if (slot >= 0) {
            match(slot, bytes, start, end);
        } else if (!recognised) {
            recognise(bytes, start, end);
        }
    }

    private int slotOf(byte[] bytes, int start, int end) {
        var slot = hash(bytes, start, end) & mask;
        while (keys[slot] != null) {
            if (Arrays.equals(keys[slot], 0, keys[slot].length, bytes, start, end)) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void match(int slot, byte[] bytes, int start, int end) {
        if (PkIndexEntry.isDeleteRow(bytes, start, end)) {
            rows[slot] = null;
            recognised = true;
            return;
        }
        try {
            final var parsed = PkIndexEntry.fromIndexFileBytes(dbName, collName, bytes, start, end);
            parsed.setValue(ids[slot]);
            rows[slot] = parsed;
            recognised = true;
        } catch (RuntimeException e) {
            malformed = true;
        }
    }

    private void recognise(byte[] bytes, int start, int end) {
        if (PkIndexEntry.isDeleteRow(bytes, start, end)) {
            recognised = true;
            return;
        }
        try {
            PkIndexEntry.fromIndexFileBytes(dbName, collName, bytes, start, end);
            recognised = true;
        } catch (RuntimeException e) {
            malformed = true;
        }
    }

    private Map<String, PkIndexEntry> found() {
        final var result = new HashMap<String, PkIndexEntry>();
        for (var slot = 0; slot < rows.length; slot++) {
            if (rows[slot] != null) {
                result.put(ids[slot], rows[slot]);
            }
        }
        return result;
    }

    private static int hash(byte[] bytes, int start, int end) {
        var hash = 1;
        for (var index = start; index < end; index++) {
            hash = 31 * hash + bytes[index];
        }
        return hash ^ (hash >>> 16);
    }
}
