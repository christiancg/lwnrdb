package org.techhouse.client;

import java.io.PrintStream;
import java.util.Iterator;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public record CommandRunner(Connection connection, PrintStream out, boolean printReplies, boolean merging) {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final String DATABASE_EXISTS = "409-2";

    public int run(Iterator<String> lines) {
        var lineNumber = 0;
        while (lines.hasNext()) {
            final var line = lines.next();
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            final JsonObject reply;
            try {
                reply = connection.sendLine(line);
            } catch (ClientException e) {
                throw new ClientException("line " + lineNumber + ": " + e.getMessage(), e.exitCode(), e);
            }
            if (printReplies) {
                out.println(eJson.toJson(reply));
            }
            if (!succeeded(reply)) {
                if (!printReplies) {
                    out.println("line " + lineNumber + ": " + eJson.toJson(reply));
                }
                return ClientException.FAILED;
            }
        }
        return ClientException.OK;
    }

    public boolean succeeded(JsonObject reply) {
        if (Connection.isOk(reply)) {
            return true;
        }
        return merging && "CREATE_DATABASE".equals(Connection.stringOf(reply, Requests.TYPE))
                && DATABASE_EXISTS.equals(Connection.errorCodeOf(reply));
    }
}
