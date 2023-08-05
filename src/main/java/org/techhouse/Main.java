package org.techhouse;

import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public class Main {
    private final static Configuration config = Configuration.getInstance();

    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    private static int getPort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (Exception e) {
                throw new InvalidPortException(args[0], e);
            }
        } else {
            return config.getPort();
        }
    }

    public static void main(String[] args) {
        fs.createBaseDbPath();
        var port = getPort(args);
        var server = new SocketServer(port);
        server.serve();
    }
}
