package org.techhouse.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;

interface AdminCacheDelegate {
    AdminCache adminCache();

    default void loadAdminData() throws IOException {
        adminCache().loadAdminData();
    }

    default long selectPageForInsert(String dbName, String collName, int entryByteSize) {
        return adminCache().selectPageForInsert(dbName, collName, entryByteSize);
    }

    default PkIndexEntry getPkIndexAdminDbEntry(String dbName) {
        return adminCache().getPkIndexAdminDbEntry(dbName);
    }

    default void putPkIndexAdminDbEntry(PkIndexEntry adminPkIndexAdminDbEntry) {
        adminCache().putPkIndexAdminDbEntry(adminPkIndexAdminDbEntry);
    }

    default AdminDbEntry getAdminDbEntry(String dbName) {
        return adminCache().getAdminDbEntry(dbName);
    }

    default Collection<AdminDbEntry> getAllAdminDbEntries() {
        return adminCache().getAllAdminDbEntries();
    }

    default List<String> getUserDatabaseNames() {
        return adminCache().getUserDatabaseNames();
    }

    default List<String> getCollectionNamesForDatabase(String dbName) {
        return adminCache().getCollectionNamesForDatabase(dbName);
    }

    default PkIndexEntry getPkIndexAdminCollEntry(String collIdentifier) {
        return adminCache().getPkIndexAdminCollEntry(collIdentifier);
    }

    default void putPkIndexAdminCollEntry(PkIndexEntry adminPkIndexAdminCollEntry) {
        adminCache().putPkIndexAdminCollEntry(adminPkIndexAdminCollEntry);
    }

    default AdminCollEntry getAdminCollectionEntry(String dbName, String collName) {
        return adminCache().getAdminCollectionEntry(dbName, collName);
    }

    default List<AdminPageEntry> getAdminPageEntries(String dbName, String collName) {
        return adminCache().getAdminPageEntries(dbName, collName);
    }

    default AdminPageEntry getAdminPageEntry(String dbName, String collName, long page) {
        return adminCache().getAdminPageEntry(dbName, collName, page);
    }

    default void putAdminPageEntries(String dbName, String collName, List<AdminPageEntry> adminPageEntries) {
        adminCache().putAdminPageEntries(dbName, collName, adminPageEntries);
    }

    default void addAdminPageEntries(String dbName, String collName, AdminPageEntry adminPageEntry) {
        adminCache().addAdminPageEntries(dbName, collName, adminPageEntry);
    }

    default void updatePageSizeInMemory(String dbName, String collName, long page, long bytesDelta) {
        adminCache().updatePageSizeInMemory(dbName, collName, page, bytesDelta);
    }

    default List<PkIndexEntry> getAdminPagePkIndexes(String dbName, String collName) {
        return adminCache().getAdminPagePkIndexes(dbName, collName);
    }

    default void removeAdminPageEntries(String dbName, String collName) {
        adminCache().removeAdminPageEntries(dbName, collName);
    }

    default void putAdminDbEntry(AdminDbEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminDbEntry(dbEntry, indexEntry);
    }

    default void removeAdminDbEntry(String dbName) {
        adminCache().removeAdminDbEntry(dbName);
    }

    default void putAdminCollectionEntry(AdminCollEntry dbEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminCollectionEntry(dbEntry, indexEntry);
    }

    default void removeAdminCollEntry(String collIdentifier) {
        adminCache().removeAdminCollEntry(collIdentifier);
    }

    default Set<String> getIndexesForCollection(String dbName, String collName) {
        return adminCache().getIndexesForCollection(dbName, collName);
    }

    default JsonObject getCollectionSchema(String dbName, String collName) {
        return adminCache().getCollectionSchema(dbName, collName);
    }

    default void putCollectionSchema(String dbName, String collName, JsonObject schema) {
        adminCache().putCollectionSchema(dbName, collName, schema);
    }

    default void removeCollectionSchema(String dbName, String collName) {
        adminCache().removeCollectionSchema(dbName, collName);
    }

    default void removeCollectionSchemasForDatabase(String dbName) {
        adminCache().removeCollectionSchemasForDatabase(dbName);
    }

    default JsonObject loadSchemaUncached(String dbName, String collName) {
        return adminCache().loadSchemaUncached(dbName, collName);
    }

    default ProcedureDefinition getProcedure(String dbName, String name) {
        return adminCache().getProcedure(dbName, name);
    }

    default void putProcedure(String dbName, ProcedureDefinition definition) {
        adminCache().putProcedure(dbName, definition);
    }

    default void removeProcedure(String dbName, String name) {
        adminCache().removeProcedure(dbName, name);
    }

    default void removeProceduresForDatabase(String dbName) {
        adminCache().removeProceduresForDatabase(dbName);
    }

    default ProcedureDefinition loadProcedureUncached(String dbName, String name) {
        return adminCache().loadProcedureUncached(dbName, name);
    }

    default List<TriggerDefinition> getTriggersFor(String dbName, String collName) {
        return adminCache().getTriggersFor(dbName, collName);
    }

    default void putTriggers(String dbName, String collName, List<TriggerDefinition> definitions) {
        adminCache().putTriggers(dbName, collName, definitions);
    }

    default void removeTriggers(String dbName, String collName) {
        adminCache().removeTriggers(dbName, collName);
    }

    default void removeTriggersMatching(Predicate<String> keyMatches) {
        adminCache().removeTriggersMatching(keyMatches);
    }

    default List<TriggerDefinition> loadTriggersUncached(String dbName, String collName) {
        return adminCache().loadTriggersUncached(dbName, collName);
    }

    default ScheduleDefinition getSchedule(String dbName, String name) {
        return adminCache().getSchedule(dbName, name);
    }

    default ScheduleDefinition loadScheduleUncached(String dbName, String name) {
        return adminCache().loadScheduleUncached(dbName, name);
    }

    default void putSchedule(String dbName, ScheduleDefinition definition) {
        adminCache().putSchedule(dbName, definition);
    }

    default void removeSchedule(String dbName, String name) {
        adminCache().removeSchedule(dbName, name);
    }

    default void removeSchedulesForDatabase(String dbName) {
        adminCache().removeSchedulesForDatabase(dbName);
    }

    default void removeSchedulesMatching(Predicate<String> keyMatches) {
        adminCache().removeSchedulesMatching(keyMatches);
    }

    default MetadataCacheStats metadataCacheStats() {
        return adminCache().metadataCacheStats();
    }

    default AdminUserEntry getAdminUserEntry(String username) {
        return adminCache().getAdminUserEntry(username);
    }

    default Collection<AdminUserEntry> getAllAdminUserEntries() {
        return adminCache().getAllAdminUserEntries();
    }

    default void putAdminUserEntry(AdminUserEntry userEntry, PkIndexEntry indexEntry) {
        adminCache().putAdminUserEntry(userEntry, indexEntry);
    }

    default void removeAdminUserEntry(String username) {
        adminCache().removeAdminUserEntry(username);
    }

    default PkIndexEntry getPkIndexAdminUserEntry(String username) {
        return adminCache().getPkIndexAdminUserEntry(username);
    }

    default PkIndexEntry getPkIndexCollectionUsage(String usageId) {
        return adminCache().getPkIndexCollectionUsage(usageId);
    }

    default void putPkIndexCollectionUsage(PkIndexEntry indexEntry) {
        adminCache().putPkIndexCollectionUsage(indexEntry);
    }

    default void removePkIndexCollectionUsage(String usageId) {
        adminCache().removePkIndexCollectionUsage(usageId);
    }

    default Map<String, PkIndexEntry> getCollectionUsagePkIndexes() {
        return adminCache().getCollectionUsagePkIndexes();
    }

    default PkIndexEntry getPkIndexTransaction(String opId) {
        return adminCache().getPkIndexTransaction(opId);
    }

    default void putPkIndexTransaction(PkIndexEntry indexEntry) {
        adminCache().putPkIndexTransaction(indexEntry);
    }

    default void removePkIndexTransaction(String opId) {
        adminCache().removePkIndexTransaction(opId);
    }

    default Map<String, PkIndexEntry> getTransactionPkIndexes() {
        return adminCache().getTransactionPkIndexes();
    }

    default PkIndexEntry getPkIndexTriggerRun(String recordId) {
        return adminCache().getPkIndexTriggerRun(recordId);
    }

    default void putPkIndexTriggerRun(PkIndexEntry indexEntry) {
        adminCache().putPkIndexTriggerRun(indexEntry);
    }

    default void removePkIndexTriggerRun(String recordId) {
        adminCache().removePkIndexTriggerRun(recordId);
    }

    default Map<String, PkIndexEntry> getTriggerRunPkIndexes() {
        return adminCache().getTriggerRunPkIndexes();
    }
}
