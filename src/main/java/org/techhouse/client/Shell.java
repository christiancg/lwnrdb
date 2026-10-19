package org.techhouse.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;

public final class Shell {
    public static final String PROMPT = "lwnrdb> ";
    private static final String INDENT = "  ";
    private static final Set<String> ENDINGS = Set.of("exit", "quit");
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final Connection connection;
    private final InputStream in;
    private final PrintStream out;

    public Shell(Connection connection, InputStream in, PrintStream out) {
        this.connection = connection;
        this.in = in;
        this.out = out;
    }

    public int run() {
        final var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        while (true) {
            out.print(PROMPT);
            out.flush();
            final var line = readLine(reader);
            if (line == null || ENDINGS.contains(line.trim())) {
                return ClientException.OK;
            }
            if (!line.isBlank()) {
                out.println(eJson.toJson(connection.sendLine(line), INDENT));
            }
        }
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new ClientException("cannot read the input: " + e, ClientException.FAILED, e);
        }
    }
}
