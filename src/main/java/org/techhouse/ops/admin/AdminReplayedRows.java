package org.techhouse.ops.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class AdminReplayedRows {
    private static final Logger logger = Logger.logFor(AdminReplayedRows.class);
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static Cache cache = IocContainer.get(Cache.class);

    private AdminReplayedRows() {
    }

    static void publish(String dbName, String collName, List<PkIndexEntry> rows) {
        if (!Globals.ADMIN_DB_NAME.equals(dbName) || rows.isEmpty()) {
            return;
        }
        final var finalRows = lastRowPerId(rows);
        switch (collName) {
            case Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME ->
                publish(finalRows, cache::putPkIndexTransaction, cache::removePkIndexTransaction);
            case Globals.ADMIN_COLLECTION_USAGE_NAME ->
                publish(finalRows, cache::putPkIndexCollectionUsage, cache::removePkIndexCollectionUsage);
            case Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME ->
                publish(finalRows, cache::putPkIndexTriggerRun, cache::removePkIndexTriggerRun);
            case Globals.ADMIN_DATABASES_COLLECTION_NAME -> publishEntries(finalRows, AdminDbEntry::fromJsonObject,
                    cache::putAdminDbEntry, cache::removeAdminDbEntry);
            case Globals.ADMIN_COLLECTIONS_COLLECTION_NAME -> publishEntries(finalRows, AdminCollEntry::fromJsonObject,
                    cache::putAdminCollectionEntry, cache::removeAdminCollEntry);
            case Globals.ADMIN_USERS_COLLECTION_NAME -> publishEntries(finalRows, AdminUserEntry::fromJsonObject,
                    AdminReplayedRows::putUser, AdminReplayedRows::removeUser);
            default -> logger.warning("Applied " + rows.size() + " logged write(s) of " + dbName
                    + Globals.COLL_IDENTIFIER_SEPARATOR + collName + " that an earlier write could not; the cached"
                    + " records of that collection catch up at the next startup");
        }
    }

    private static void putUser(AdminUserEntry user, PkIndexEntry row) {
        final var previous = cache.getAdminUserEntry(user.get_id());
        cache.putAdminUserEntry(user, row);
        if (previous != null && !Objects.equals(previous.getPasswordHash(), user.getPasswordHash())) {
            clientTracker.deauthenticateUser(user.get_id());
        }
    }

    private static void removeUser(String username) {
        cache.removeAdminUserEntry(username);
        clientTracker.deauthenticateUser(username);
    }

    private static List<PkIndexEntry> lastRowPerId(List<PkIndexEntry> rows) {
        final var last = new LinkedHashMap<String, PkIndexEntry>();
        for (final var row : rows) {
            last.remove(row.getValue());
            last.put(row.getValue(), row);
        }
        return List.copyOf(last.values());
    }

    private static void publish(List<PkIndexEntry> rows, Consumer<PkIndexEntry> put, Consumer<String> remove) {
        final var removed = new ArrayList<String>();
        for (final var row : rows) {
            if (row.getLength() == 0L) {
                removed.add(row.getValue());
            } else {
                put.accept(row);
            }
        }
        TransactionRecords.unpublishOrder(removed).forEach(remove);
    }

    private static <T extends DbEntry> void publishEntries(List<PkIndexEntry> rows, Function<JsonObject, T> factory,
            BiConsumer<T, PkIndexEntry> put, Consumer<String> remove) {
        final var removed = new ArrayList<String>();
        for (final var row : rows) {
            if (row.getLength() == 0L) {
                removed.add(row.getValue());
                continue;
            }
            try {
                put.accept(factory.apply(fs.getById(row).getData()), row);
            } catch (Exception e) {
                logger.warning("Applied the logged write of admin record " + row.getValue() + " but could not read"
                        + " it back; its cached copy catches up at the next startup: " + e.getMessage());
            }
        }
        removed.forEach(remove);
    }
}
