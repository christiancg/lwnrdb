package org.techhouse.client;

import java.io.PrintStream;
import java.util.Iterator;

public record CommandRunner(Connection connection, PrintStream out, boolean printReplies, boolean merging) {
    private static final String DATABASE_EXISTS = "409-2";

    public int run(Iterator<String> lines) {
        var lineNumber = 0;
        while (lines.hasNext()) {
            final var line = lines.next();
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                if (!sendOne(line, lineNumber)) {
                    return ClientException.FAILED;
                }
            } catch (ClientException e) {
                throw new ClientException("line " + lineNumber + ": " + e.getMessage(), e.exitCode(), e);
            }
        }
        return ClientException.OK;
    }

    private boolean sendOne(String line, int lineNumber) {
        if (printReplies) {
            final var header = connection.relayLine(line, out);
            out.println();
            return succeeded(header);
        }
        final var raw = connection.sendRawLine(line);
        if (succeeded(ReplyHeader.of(raw))) {
            return true;
        }
        out.println("line " + lineNumber + ": " + raw);
        return false;
    }

    public boolean succeeded(ReplyHeader reply) {
        if (reply.isOk()) {
            return true;
        }
        return merging && "CREATE_DATABASE".equals(reply.type()) && DATABASE_EXISTS.equals(reply.errorCode());
    }
}
