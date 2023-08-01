package org.techhouse;

import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public class Main {
    private final static Configuration config = Configuration.getInstance();

    private static final FileSystem fs = IocContainer.get(FileSystem.class);

    public static void main(String[] args) {
        fs.createBaseDbPath();
        var port = 0;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (Exception e) {
                System.out.println("Port must be an integer");
                return;
            }
        } else {
            port = config.getPort();
        }
        var server = new SocketServer(port);
        server.serve();
    }
}
