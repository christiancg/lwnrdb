package org.techhouse.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class Restore {
    public static final int PROGRESS_EVERY = 1000;
    private static final int GZIP_MAGIC_FIRST = 0x1f;
    private static final int GZIP_MAGIC_SECOND = 0x8b;
    private static final String CREATE_DATABASE_PREFIX = "{\"type\":\"CREATE_DATABASE\"";

    private final Connection connection;
    private final InputStream in;
    private final PrintStream err;

    public Restore(Connection connection, InputStream in, PrintStream err) {
        this.connection = connection;
        this.in = in;
        this.err = err;
    }

    public int run() {
        final var existing = Set.copyOf(DumpScope.stringsOf(connection.sendOk(Requests.listDatabases()), "databases"));
        final var lines = new CountingLines(readerOf(in).lines().iterator(), existing);
        final var runner = new CommandRunner(connection, err, false, true);
        var restored = 0;
        try {
            final var result = runner.run(lines);
            restored = result == ClientException.OK ? lines.sent : lines.sent - 1;
            return result;
        } catch (UncheckedIOException e) {
            restored = lines.sent;
            err.println("cannot read the backup after line " + lines.handed + ": " + e.getCause());
            return ClientException.FAILED;
        } catch (ClientException e) {
            restored = lines.sent - 1;
            throw e;
        } finally {
            err.println("restored " + restored + " requests"
                    + (lines.skipped > 0 ? ", skipped " + lines.skipped + " existing databases" : ""));
        }
    }

    private static BufferedReader readerOf(InputStream in) {
        try {
            final var pushback = new PushbackInputStream(in, 2);
            final var head = pushback.readNBytes(2);
            pushback.unread(head);
            final var gzipped = head.length == 2 && (head[0] & 0xff) == GZIP_MAGIC_FIRST
                    && (head[1] & 0xff) == GZIP_MAGIC_SECOND;
            final InputStream decoded = gzipped ? new GZIPInputStream(pushback) : pushback;
            return new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClientException("cannot read the backup: " + e, ClientException.FAILED, e);
        }
    }

    private final class CountingLines implements Iterator<String> {
        private final Iterator<String> lines;
        private final Set<String> existing;
        private int handed;
        private int sent;
        private int skipped;

        CountingLines(Iterator<String> lines, Set<String> existing) {
            this.lines = lines;
            this.existing = existing;
        }

        @Override
        public boolean hasNext() {
            return lines.hasNext();
        }

        @Override
        public String next() {
            final var line = lines.next();
            handed++;
            if (isExistingDatabaseCreation(line)) {
                skipped++;
                return "";
            }
            if (!line.isBlank()) {
                if (sent > 0 && sent % PROGRESS_EVERY == 0) {
                    err.println("restored " + sent + " requests");
                }
                sent++;
            }
            return line;
        }

        private boolean isExistingDatabaseCreation(String line) {
            if (!line.stripLeading().startsWith(CREATE_DATABASE_PREFIX)) {
                return false;
            }
            final var database = Connection.stringOf(Connection.parseReply(line), Requests.DATABASE);
            return database != null && existing.contains(database);
        }
    }
}
