package org.techhouse.bckg_ops;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ex.InvalidCronException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.schedule.CronExpression;

/**
 * The in-memory table of every known schedule and the instant it is next due at. Held in memory and never
 * persisted: a persisted {@code lastRunAt} would mean an admin write per run and would churn the admin
 * epoch, and it is not needed — a node taking a schedule over computes the next <em>future</em> occurrence,
 * which is exactly what makes firing at-most-once per due instant rather than at-least-once.
 *
 * <p>
 * The DDL path updates one database's entries directly; {@code scheduleRefreshMs} rebuilds everything as the
 * safety net for schedules that arrived through cluster replication or admin anti-entropy.
 */
public class ScheduleRegistry {
    private final Logger logger = Logger.logFor(ScheduleRegistry.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    // Names already reported as unfirable, so an unsatisfiable cron is warned about once rather than
    // once per tick.
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    /** One schedule plus the derived state the scheduler needs: its parsed cron and its next due instant. */
    public static final class Entry {
        private final String dbName;
        private final ScheduleDefinition definition;
        private final CronExpression cron;
        private volatile long nextRunAt;

        Entry(String dbName, ScheduleDefinition definition, CronExpression cron, long nextRunAt) {
            this.dbName = dbName;
            this.definition = definition;
            this.cron = cron;
            this.nextRunAt = nextRunAt;
        }

        public String getDbName() {
            return dbName;
        }

        public ScheduleDefinition getDefinition() {
            return definition;
        }

        public String getName() {
            return definition.getName();
        }

        public String key() {
            return Cache.getCollectionIdentifier(dbName, definition.getName());
        }

        public long getNextRunAt() {
            return nextRunAt;
        }

        public void setNextRunAt(long nextRunAt) {
            this.nextRunAt = nextRunAt;
        }

        public CronExpression getCron() {
            return cron;
        }
    }

    public void loadAll() {
        for (final var dbName : new ArrayList<>(cache.getUserDatabaseNames())) {
            reload(dbName);
        }
    }

    public void reload(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        final var seen = new ArrayList<String>();
        for (final var name : fs.listScheduleNames(dbName)) {
            final var definition = cache.getSchedule(dbName, name);
            if (definition == null) {
                continue;
            }
            final var key = Cache.getCollectionIdentifier(dbName, name);
            seen.add(key);
            put(key, dbName, definition);
        }
        entries.keySet().removeIf(key -> key.startsWith(prefix) && !seen.contains(key));
    }

    public void removeDatabase(String dbName) {
        final var prefix = dbName + Globals.COLL_IDENTIFIER_SEPARATOR;
        entries.keySet().removeIf(key -> key.startsWith(prefix));
        warned.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void clear() {
        entries.clear();
        warned.clear();
    }

    public Collection<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry get(String dbName, String name) {
        return entries.get(Cache.getCollectionIdentifier(dbName, name));
    }

    public int size() {
        return entries.size();
    }

    /**
     * The instant this schedule is next due at, or 0 when it can never fire again (an unsatisfiable cron).
     * Computed strictly after {@code from}, which is what keeps a handed-over schedule from replaying an
     * instant the previous owner may already have run.
     */
    public long nextRunAfter(Entry entry, long from) {
        if (entry.getCron() == null) {
            return from + Math.max(1L, entry.getDefinition().getIntervalMs());
        }
        final var next = entry.getCron()
                .nextAfter(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), zone()));
        if (next == null) {
            warnOnce(entry.key(), "cron '" + entry.getDefinition().getCron() + "' has no occurrence within the "
                    + "search horizon; the schedule will not fire");
            return 0L;
        }
        return next.toInstant().toEpochMilli();
    }

    public void warnOnce(String key, String message) {
        if (warned.putIfAbsent(key, Boolean.TRUE) == null) {
            logger.warning("Schedule '" + key + "': " + message);
        }
    }

    private void put(String key, String dbName, ScheduleDefinition definition) {
        final var existing = entries.get(key);
        if (existing != null && existing.getDefinition().equals(definition)) {
            return;
        }
        final CronExpression cron;
        try {
            cron = definition.getCron() == null || definition.getCron().isBlank()
                    ? null
                    : CronExpression.parse(definition.getCron());
        } catch (InvalidCronException e) {
            // A save parses the cron first, so this only happens for a file edited or replicated from a
            // version that accepted something this one does not. Skipping beats firing on a guess.
            warnOnce(key, e.getMessage());
            entries.remove(key);
            return;
        }
        final var entry = new Entry(dbName, definition, cron, 0L);
        warned.remove(key);
        entry.setNextRunAt(nextRunAfter(entry, System.currentTimeMillis()));
        entries.put(key, entry);
    }

    private ZoneId zone() {
        return ZoneId.of(configuration.getScriptTimeZone());
    }
}
