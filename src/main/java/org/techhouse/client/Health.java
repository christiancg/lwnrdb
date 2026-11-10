package org.techhouse.client;

import java.io.PrintStream;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class Health {
    public static final String READY = "READY";
    private static final String SERVER = "server";
    private static final String STATE = "state";
    private static final EJson eJson = IocContainer.get(EJson.class);

    private Health() {
    }

    public static int check(Connection connection, PrintStream out) {
        final var raw = connection.sendRawLine(eJson.toJson(Requests.serverInfo()));
        out.println(raw);
        return READY.equals(stateOf(Connection.parseReply(raw))) ? ClientException.OK : ClientException.FAILED;
    }

    private static String stateOf(JsonObject reply) {
        final var server = reply.get(SERVER);
        return server != null && server.isJsonObject() ? Connection.stringOf(server.asJsonObject(), STATE) : null;
    }
}
