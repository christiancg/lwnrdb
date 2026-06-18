package org.techhouse.data.admin;

import java.util.Objects;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

public class AdminPageEntry extends DbEntry {
    private static final String PAGE_FIELD_NAME = "page";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";
    private static final String PAGE_SIZE_FIELD_NAME = "size";
    private long page;
    private int entryCount;
    private long pageSize;

    public AdminPageEntry(String dbName, String collectionName) {
        this(dbName, collectionName, 0L);
    }

    public AdminPageEntry(String dbName, String collectionName, long page) {
        setDatabaseName(Globals.ADMIN_DB_NAME);
        setCollectionName(String.format(Globals.ADMIN_PAGES_PER_COLLECTION_NAME, dbName, collectionName));
        this.page = page;
        set_id(buildId(dbName, collectionName, page));
        setData(new JsonObject());
        syncData();
    }

    public static AdminPageEntry fromJsonObject(String dbName, String collName, JsonObject object) {
        final var result = new AdminPageEntry(dbName, collName);
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        result.page = object.get(PAGE_FIELD_NAME).asJsonNumber().getValue().longValue();
        result.entryCount = object.get(ENTRY_COUNT_FIELD_NAME).asJsonNumber().getValue().intValue();
        result.pageSize = object.get(PAGE_SIZE_FIELD_NAME).asJsonNumber().getValue().longValue();
        result.syncData();
        return result;
    }

    public static String buildId(String dbName, String collName, long page) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName + Globals.COLL_IDENTIFIER_SEPARATOR + page;
    }

    @Override
    public void setPage(long page) {
        this.page = page;
        syncData();
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
        syncData();
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
        syncData();
    }

    private void syncData() {
        final var data = getData();
        if (data == null) {
            return;
        }
        data.addProperty(PAGE_FIELD_NAME, page);
        data.addProperty(ENTRY_COUNT_FIELD_NAME, entryCount);
        data.addProperty(PAGE_SIZE_FIELD_NAME, pageSize);
    }

    @Override
    public long getPage() {
        return page;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public long getPageSize() {
        return pageSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AdminPageEntry that))
            return false;
        if (!super.equals(o))
            return false;
        return page == that.page && entryCount == that.entryCount && pageSize == that.pageSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), page, entryCount, pageSize);
    }

    @Override
    public String toString() {
        return "AdminPageEntry(super=" + super.toString() + ", page=" + page + ", entryCount=" + entryCount
                + ", pageSize=" + pageSize + ")";
    }
}
