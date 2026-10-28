package org.techhouse.client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import org.techhouse.config.ConfigKey;

public final class ClientMain {
    public static final String USAGE = String.format(
            "Usage:%n" + "  java -jar lwnrdb.jar --shell   [connection]%n"
                    + "  java -jar lwnrdb.jar --exec '<json>' [--exec '<json>' ...] [connection]%n"
                    + "  java -jar lwnrdb.jar --exec [connection]                          < commands%n"
                    + "  java -jar lwnrdb.jar --dump [--databases a,b | --collections db.coll,...] [--compress]"
                    + " [connection] > backup.ldump%n"
                    + "  java -jar lwnrdb.jar --restore [connection]                       < backup.ldump%n" + "%n"
                    + "Connection:%n" + "  --host <host>          default %s%n"
                    + "  --port <port>          default %s%n" + "  --user <user>          default %s%n"
                    + "  --password <password>  or the %s environment variable; --shell prompts on a terminal%n"
                    + "  --tls [--insecure]     TLS, verified against the JVM trust store unless --insecure%n" + "%n"
                    + "Exit codes: 0 success, 1 a command or the dump/restore failed, 2 usage error,"
                    + " 3 cannot connect or authenticate%n",
            ClientOptions.DEFAULT_HOST, ConfigKey.PORT.defaultValue(), ClientOptions.DEFAULT_USER,
            ClientOptions.PASSWORD_ENV);

    private ClientMain() {
    }

    public static boolean isClientInvocation(String[] args) {
        return args.length > 0 && args[0].startsWith("--");
    }

    public static int run(String[] args) {
        return run(args, System.getenv(), System.in, System.out, System.err);
    }

    public static int run(String[] args, Map<String, String> env, InputStream in, PrintStream out, PrintStream err) {
        try {
            final var options = ClientOptions.parse(args, env);
            final var password = passwordFor(options);
            try (var connection = Connection.open(options)) {
                connection.authenticate(options.user(), password);
                return dispatch(options, connection, in, out, err);
            }
        } catch (ClientException e) {
            err.println(e.getMessage());
            if (e.exitCode() == ClientException.USAGE) {
                err.print(USAGE);
            }
            return e.exitCode();
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static String passwordFor(ClientOptions options) {
        if (options.password() != null) {
            return options.password();
        }
        final var console = System.console();
        if (options.mode() == ClientMode.SHELL && console != null && console.isTerminal()) {
            final var typed = console.readPassword("Password: ");
            if (typed != null) {
                return new String(typed);
            }
        }
        throw ClientOptions.usage("a password is required: pass --password or set " + ClientOptions.PASSWORD_ENV);
    }

    private static int dispatch(ClientOptions options, Connection connection, InputStream in, PrintStream out,
            PrintStream err) {
        return switch (options.mode()) {
            case SHELL -> new Shell(connection, in, out).run();
            case EXEC -> new CommandRunner(connection, out, true, false).run(commandsOf(options, in));
            case DUMP -> new Dump(connection, options, out, err).run();
            case RESTORE -> new Restore(connection, in, err).run();
        };
    }

    private static Iterator<String> commandsOf(ClientOptions options, InputStream in) {
        if (!options.commands().isEmpty()) {
            return options.commands().iterator();
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().iterator();
    }
}
