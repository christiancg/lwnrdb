package org.techhouse.data.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminPageEntry extends DbEntry {
    private static final String PAGE_FIELD_NAME = "page";
    private static final String ENTRY_COUNT_FIELD_NAME = "entryCount";
    private static final String PAGE_SIZE_FIELD_NAME = "size";
    private int page;
    private int entryCount;
    private int pageSize;

    public AdminPageEntry(String collectionName) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME.replace("{}", collectionName));
    }

    public static AdminPageEntry fromJsonObject(String collName, JsonObject object) {
        final var result = new AdminPageEntry(collName);
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        result.setPage(object.get(PAGE_FIELD_NAME).asJsonNumber().getValue().intValue());
        result.setEntryCount(object.get(ENTRY_COUNT_FIELD_NAME).asJsonNumber().getValue().intValue());
        result.setPageSize(object.get(PAGE_SIZE_FIELD_NAME).asJsonNumber().getValue().intValue());
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_PAGES_PER_COLLECTION_NAME.replace("{}", collName));
        return result;
    }

    @Override
    public void setDatabaseName(String value) {}
    @Override
    public void setCollectionName(String value) {}
}
