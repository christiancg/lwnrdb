package org.techhouse;

import java.io.IOException;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.log.Logger;

public final class DeviceSyncGuard {
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(DeviceSyncGuard.class);

    private DeviceSyncGuard() {
    }

    public static void fallBackWhenUnsyncable(FileSystem fs) {
        try {
            fs.verifyDeviceSync();
        } catch (IOException e) {
            configuration.override(ConfigKey.SYNC_WRITES, "false");
            logger.warning("DURABILITY WARNING: syncWrites=true, but this platform cannot sync a directory to the"
                    + " device (" + e.getMessage() + "), so a created or renamed file could be lost on power loss."
                    + " Falling back to syncWrites=false; set it explicitly in lwnrdb.cfg to silence this warning.");
        }
    }
}
