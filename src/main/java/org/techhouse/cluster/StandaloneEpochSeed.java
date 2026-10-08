package org.techhouse.cluster;

import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class StandaloneEpochSeed {
    private static final Logger logger = Logger.logFor(StandaloneEpochSeed.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);

    private StandaloneEpochSeed() {
    }

    public static void seedIfPopulated() {
        if (holdsStandaloneData() && adminEpoch.seedFromStandalone()) {
            logger.info("This node holds admin data from before it was clustered; its admin epoch starts at 1 so a"
                    + " fresh peer conforms to it instead of the other way round");
        }
    }

    private static boolean holdsStandaloneData() {
        if (!cache.getAllAdminDbEntries().isEmpty()) {
            return true;
        }
        final var config = Configuration.getInstance();
        final var bootstrapAdmin = config.getDefaultAdminUsername();
        for (final AdminUserEntry user : cache.getAllAdminUserEntries()) {
            if (!bootstrapAdmin.equals(user.get_id())
                    || !PasswordHasher.verify(config.getDefaultAdminPassword(), user.getPasswordHash())) {
                return true;
            }
        }
        return false;
    }
}
