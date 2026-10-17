package org.techhouse.ops;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import org.techhouse.cache.Cache;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.RestampedRecords;

public final class DefinerRevocation {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Logger logger = Logger.logFor(DefinerRevocation.class);

    private DefinerRevocation() {
    }

    private interface Disabling {
        boolean run() throws IOException, InterruptedException;
    }

    public static void forDefiner(String username, RestampedRecords restamped) {
        final List<String> dbNames = cache.getAllAdminDbEntries().stream().map(AdminDbEntry::get_id).toList();
        for (final var dbName : dbNames) {
            if (!revoke(dbName, username::equals, restamped)) {
                return;
            }
        }
    }

    public static void forDatabase(String dbName, RestampedRecords restamped) {
        revoke(dbName, _ -> true, restamped);
    }

    private static boolean revoke(String dbName, Predicate<String> definerInScope, RestampedRecords restamped) {
        return revokeTriggers(dbName, definerInScope, restamped) && revokeSchedules(dbName, definerInScope, restamped);
    }

    private static boolean revokeTriggers(String dbName, Predicate<String> definerInScope, RestampedRecords restamped) {
        for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
            var disabledAny = false;
            for (final var trigger : List.copyOf(cache.getTriggersFor(dbName, collName))) {
                if (trigger.isBefore() || outOfScope(trigger.isEnabled(), trigger.getDefiner(), definerInScope)) {
                    continue;
                }
                final var refusal = DefinerAuthority.refusal(trigger.getDefiner(), dbName, collName);
                if (refusal == null) {
                    continue;
                }
                final var outcome = disable(describe(dbName, collName, trigger), refusal,
                        () -> TriggerOperationHelper.disable(dbName, collName, trigger, refusal));
                if (outcome == Outcome.INTERRUPTED) {
                    return false;
                }
                disabledAny |= outcome == Outcome.DISABLED;
            }
            if (disabledAny) {
                restamped.add(AdminRecordKey.triggers(dbName, collName));
            }
        }
        return true;
    }

    private static boolean revokeSchedules(String dbName, Predicate<String> definerInScope,
            RestampedRecords restamped) {
        final List<ScheduleDefinition> schedules;
        try {
            schedules = ScheduleOperationHelper.schedulesIn(dbName);
        } catch (IOException e) {
            logger.error("Could not read the schedules of database '" + dbName + "' to re-check their definers; a"
                    + " schedule whose definer lost its rights is disabled when it next fires", e);
            return true;
        }
        for (final var schedule : schedules) {
            if (outOfScope(schedule.isEnabled(), schedule.getDefiner(), definerInScope)) {
                continue;
            }
            final var refusal = DefinerAuthority.refusal(schedule.getDefiner(), dbName, null);
            if (refusal == null) {
                continue;
            }
            final var outcome = disable("Schedule '" + schedule.getName() + "' in " + dbName, refusal,
                    () -> ScheduleOperationHelper.disable(dbName, schedule, refusal));
            if (outcome == Outcome.INTERRUPTED) {
                return false;
            }
            if (outcome == Outcome.DISABLED) {
                restamped.add(AdminRecordKey.schedule(dbName, schedule.getName()));
            }
        }
        return true;
    }

    private static boolean outOfScope(boolean enabled, String definer, Predicate<String> definerInScope) {
        return !enabled || definer == null || !definerInScope.test(definer);
    }

    private static String describe(String dbName, String collName, TriggerDefinition trigger) {
        return "Trigger '" + trigger.getName() + "' on " + dbName + "|" + collName;
    }

    private enum Outcome {
        DISABLED, UNCHANGED, INTERRUPTED
    }

    private static Outcome disable(String what, String reason, Disabling disabling) {
        try {
            if (!disabling.run()) {
                return Outcome.UNCHANGED;
            }
            logger.warning(DefinerAuthority.disabledMessage(what, reason));
            return Outcome.DISABLED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.INTERRUPTED;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not disable " + what + " after its " + reason + ": " + e.getMessage());
            return Outcome.UNCHANGED;
        }
    }
}
