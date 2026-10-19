package org.techhouse.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.techhouse.config.ConfigKey;

public record ClientOptions(ClientMode mode, String host, int port, String user, String password, boolean tls,
        boolean insecure, boolean compress, List<String> commands, List<String> databases,
        List<CollectionRef> collections) {
    public static final String PASSWORD_ENV = "LWNRDB_PASSWORD";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final String DEFAULT_USER = "admin";
    private static final int MAX_PORT = 65_535;
    private static final Pattern PORT_DIGITS = Pattern.compile("\\d{1,5}");

    public record CollectionRef(String database, String collection) {
        static CollectionRef parse(String item) {
            final var parts = item.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw usage("--collections takes db.collection items, got '" + item + "'");
            }
            return new CollectionRef(parts[0], parts[1]);
        }
    }

    public static ClientOptions parse(String[] args, Map<String, String> env) {
        final var builder = new Builder();
        var index = 0;
        while (index < args.length) {
            index = builder.accept(args, index);
        }
        return builder.build(env);
    }

    static ClientException usage(String message) {
        return new ClientException(message, ClientException.USAGE);
    }

    private static final class Builder {
        private ClientMode mode;
        private String host = DEFAULT_HOST;
        private int port = Integer.parseInt(ConfigKey.PORT.defaultValue());
        private String user = DEFAULT_USER;
        private String password;
        private boolean tls;
        private boolean insecure;
        private boolean compress;
        private final List<String> commands = new ArrayList<>();
        private final LinkedHashSet<String> databases = new LinkedHashSet<>();
        private final LinkedHashSet<CollectionRef> collections = new LinkedHashSet<>();
        private boolean databasesGiven;
        private boolean collectionsGiven;

        int accept(String[] args, int index) {
            final var flag = args[index];
            switch (flag) {
                case "--shell" -> selectMode(ClientMode.SHELL);
                case "--dump" -> selectMode(ClientMode.DUMP);
                case "--restore" -> selectMode(ClientMode.RESTORE);
                case "--exec" -> {
                    selectMode(ClientMode.EXEC);
                    if (index + 1 < args.length && !args[index + 1].startsWith("--")) {
                        commands.add(args[index + 1]);
                        return index + 2;
                    }
                }
                case "--tls" -> tls = true;
                case "--insecure" -> insecure = true;
                case "--compress" -> compress = true;
                case "--host" -> host = valueOf(args, index);
                case "--port" -> port = portOf(valueOf(args, index));
                case "--user" -> user = valueOf(args, index);
                case "--password" -> password = valueOf(args, index);
                case "--databases" -> {
                    databasesGiven = true;
                    databases.addAll(itemsOf(flag, valueOf(args, index)));
                }
                case "--collections" -> {
                    collectionsGiven = true;
                    for (final var item : itemsOf(flag, valueOf(args, index))) {
                        collections.add(CollectionRef.parse(item));
                    }
                }
                default -> throw usage("unknown argument '" + flag + "'");
            }
            return takesValue(flag) ? index + 2 : index + 1;
        }

        private static boolean takesValue(String flag) {
            return switch (flag) {
                case "--host", "--port", "--user", "--password", "--databases", "--collections" -> true;
                default -> false;
            };
        }

        private void selectMode(ClientMode selected) {
            if (mode != null && (mode != selected || selected != ClientMode.EXEC)) {
                throw usage("only one of --shell, --exec, --dump and --restore may be given");
            }
            mode = selected;
        }

        private static String valueOf(String[] args, int index) {
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw usage(args[index] + " needs a value");
            }
            return args[index + 1];
        }

        private static int portOf(String value) {
            if (PORT_DIGITS.matcher(value).matches()) {
                final var parsed = Integer.parseInt(value);
                if (parsed >= 1 && parsed <= MAX_PORT) {
                    return parsed;
                }
            }
            throw usage("--port must be a number between 1 and " + MAX_PORT + ", got '" + value + "'");
        }

        private static List<String> itemsOf(String flag, String value) {
            final var items = new ArrayList<String>();
            for (final var item : value.split(",", -1)) {
                if (item.isBlank()) {
                    throw usage(flag + " has an empty item in '" + value + "'");
                }
                items.add(item.trim());
            }
            return items;
        }

        ClientOptions build(Map<String, String> env) {
            if (mode == null) {
                throw usage("one of --shell, --exec, --dump or --restore is required");
            }
            if (insecure && !tls) {
                throw usage("--insecure only applies together with --tls");
            }
            if (databasesGiven && collectionsGiven) {
                throw usage("--databases and --collections cannot be combined");
            }
            if (mode != ClientMode.DUMP && (databasesGiven || collectionsGiven || compress)) {
                throw usage("--databases, --collections and --compress only apply to --dump");
            }
            final var resolvedPassword = password != null ? password : env.get(PASSWORD_ENV);
            return new ClientOptions(mode, host, port, user, resolvedPassword, tls, insecure, compress,
                    List.copyOf(commands), List.copyOf(databases), List.copyOf(collections));
        }
    }
}
