package org.techhouse.data.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminCollEntry extends DbEntry {

    @Data
    static class Page {
        private int page;
        private int entryCount;
        private int pageSize;
    }

    private static final String INDEXES_FIELD_NAME = "indexes";
    private static final String PAGES_FIELD_NAME = "pages";
    private Set<String> indexes;
    private List<Page> pages;

    private AdminCollEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
    }

    public AdminCollEntry(String dbName, String collName) {
        this(dbName, collName, new HashSet<>(), new ArrayList<>());
    }

    public AdminCollEntry(String dbName, String collName, Set<String> indexes, List<Page> pages) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        this.set_id(Cache.getCollectionIdentifier(dbName, collName));
        this.indexes = indexes;
        this.pages = pages;
        final var json = new JsonObject();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        json.add(INDEXES_FIELD_NAME, arr);
        final var pagesArr = new JsonArray();
        pages.forEach(page -> {
            final var pageObj = new JsonObject();
            pageObj.addProperty("page", page.getPage());
            pageObj.addProperty("entryCount", page.getEntryCount());
            pageObj.addProperty("pageSize", page.getPageSize());
            pagesArr.add(pageObj);
        });
        json.add(PAGES_FIELD_NAME, pagesArr);
        this.setData(json);
    }

    public static AdminCollEntry fromJsonObject(JsonObject object) {
        final var result = new AdminCollEntry();
        result.setData(object);
        final var id = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(id);
        final var collections = object.get(INDEXES_FIELD_NAME).asJsonArray().asList()
                .stream().map(element -> element.asJsonString().getValue())
                .collect(Collectors.toSet());
        result.setIndexes(collections);
        final var pages = object.get(PAGES_FIELD_NAME).asJsonArray().asList()
                .stream().map(element -> {
                    final var pageObj = new Page();
                    pageObj.setPage(element.asJsonObject().get("page").asJsonNumber().getValue().intValue());
                    pageObj.setEntryCount(element.asJsonObject().get("entryCount").asJsonNumber().getValue().intValue());
                    pageObj.setPageSize(element.asJsonObject().get("pageSize").asJsonNumber().getValue().intValue());
                    return pageObj;
                }).collect(Collectors.toList());
        result.setPages(pages);
        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_COLLECTIONS_COLLECTION_NAME);
        return result;
    }

    public void setIndexes(Set<String> indexes) {
        this.indexes = indexes;
        final var data = getData();
        final var arr = new JsonArray();
        indexes.forEach(arr::add);
        data.add(INDEXES_FIELD_NAME, arr);
        this.setData(data);
    }

    public int getEntryCount() {
        return this.pages.stream().map(Page::getEntryCount).reduce(0, Integer::sum);
    }

    public void setPages(List<Page> pages) {
        this.pages = pages;
        final var data = getData();
        final var pagesArr = new JsonArray();
        pages.forEach(page -> {
            final var pageObj = new JsonObject();
            pageObj.addProperty("page", page.getPage());
            pageObj.addProperty("entryCount", page.getEntryCount());
            pageObj.addProperty("pageSize", page.getPageSize());
            pagesArr.add(pageObj);
        });
        data.add(PAGES_FIELD_NAME, pagesArr);
        this.setData(data);
    }

    @Override
    public void setDatabaseName(String value) {}
    @Override
    public void setCollectionName(String value) {}
}
