package org.techhouse;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;

import java.io.IOException;

public class Main {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);

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

    public static void main(String[] args)
            throws IOException {
        LogWriter.createLogPathAndRemoveOldFiles();
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        cache.loadAdminData();
        final var port = getPort(args);
        final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
        backgroundTaskManager.startBackgroundWorkers();
        final var server = new SocketServer(port);
        server.serve();
    }
}
