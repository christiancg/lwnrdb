package org.techhouse;

import java.io.IOException;
import java.util.List;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.fs.FileSystem;
import org.techhouse.log.Logger;

public final class DeviceSyncGuard {
    private static final Logger logger = Logger.logFor(DeviceSyncGuard.class);

    private DeviceSyncGuard() {
    }

    public static void refuseUnsyncablePlatform(FileSystem fs) {
        try {
            fs.verifyDeviceSync();
        } catch (IOException e) {
            final var reason = "syncWrites=true, but this platform cannot sync a directory to the device ("
                    + e.getMessage() + "), so a created or renamed file could be lost on power loss. Set"
                    + " syncWrites=false in lwnrdb.cfg to run without that guarantee.";
            logger.fatal("Unsupported configuration, the application will not start: " + reason);
            throw new InvalidConfigurationException(List.of(reason));
        }
    }
}
