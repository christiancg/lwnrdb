package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.WriteGuard;
import org.techhouse.cluster.admin.AdminRecords;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.auth.AuthorizationChecker;

public final class DefinerAuthority {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);
    private static final Logger logger = Logger.logFor(DefinerAuthority.class);

    private DefinerAuthority() {
    }

    public static String refusal(String definer, String dbName, String collName) {
        final var user = definer == null ? null : cache.getAdminUserEntry(definer);
        if (user == null) {
            return "definer '" + definer + "' no longer exists";
        }
        if (AuthorizationChecker.mayRunAsDefiner(user, dbName, collName)) {
            return null;
        }
        final var required = collName == null
                ? "script RUN on database '" + dbName + "'"
                : "script RUN on database '" + dbName + "' and READ on collection '" + collName + "'";
        return "definer '" + definer + "' no longer holds " + required;
    }

    public static void disableTrigger(String dbName, String collName, TriggerDefinition trigger, String reason) {
        disable(AdminRecordKey.triggers(dbName, collName),
                "Trigger '" + trigger.getName() + "' on " + dbName + "|" + collName, reason,
                () -> TriggerOperationHelper.disable(dbName, collName, trigger, reason));
    }

    public static void disableSchedule(String dbName, ScheduleDefinition schedule, String reason) {
        disable(AdminRecordKey.schedule(dbName, schedule.getName()),
                "Schedule '" + schedule.getName() + "' in " + dbName, reason,
                () -> ScheduleOperationHelper.disable(dbName, schedule, reason));
    }

    private interface Disabling {
        boolean run() throws IOException, InterruptedException;
    }

    private static void disable(AdminRecordKey key, String what, String reason, Disabling disabling) {
        if (!decidesDefinitions()) {
            return;
        }
        try {
            if (!disabling.run()) {
                return;
            }
            logger.warning(disabledMessage(what, reason));
            if (clusterConfig.isEnabled()) {
                coordinator.replicateAdminRecords(AdminRecords.of(List.of(key)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not disable " + what + " after its " + reason + ": " + e.getMessage());
        }
    }

    static String disabledMessage(String what, String reason) {
        return what + " was disabled because its " + reason + "; its owner can re-enable it";
    }

    public static boolean decidesDefinitions() {
        if (!clusterConfig.isEnabled()) {
            return true;
        }
        return ownershipManager.isAdminCoordinator() && coordinator.guardAdmin().kind() != WriteGuard.Kind.NO_QUORUM
                && adminAntiEntropyService.hasCompletedAdminSync();
    }
}
