package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.client.Requests;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public class RequestsTest {
    private final EJson eJson = IocContainer.get(EJson.class);

    private String json(JsonObject request) {
        return eJson.toJson(request);
    }

    private JsonObject parse(String json) {
        return eJson.fromJson(json, JsonObject.class);
    }

    @Test
    public void test_authenticate() {
        assertEquals("{\"type\":\"AUTHENTICATE\",\"username\":\"admin\",\"password\":\"pw\"}",
                json(Requests.authenticate("admin", "pw")));
    }

    @Test
    public void test_connection_level_requests() {
        assertEquals("{\"type\":\"CLOSE_CONNECTION\"}", json(Requests.close()));
        assertEquals("{\"type\":\"LIST_DATABASES\"}", json(Requests.listDatabases()));
        assertEquals("{\"type\":\"GET_DATABASE_STATS\"}", json(Requests.stats()));
    }

    @Test
    public void test_database_requests() {
        assertEquals("{\"type\":\"LIST_COLLECTIONS\",\"databaseName\":\"shop\"}",
                json(Requests.listCollections("shop")));
        assertEquals("{\"type\":\"CREATE_DATABASE\",\"databaseName\":\"shop\"}", json(Requests.createDatabase("shop")));
        assertEquals("{\"type\":\"LIST_TRIGGERS\",\"databaseName\":\"shop\"}", json(Requests.listTriggers("shop")));
        assertEquals("{\"type\":\"LIST_PROCEDURES\",\"databaseName\":\"shop\",\"includeSource\":true}",
                json(Requests.listProcedures("shop", true)));
        assertEquals("{\"type\":\"LIST_SCHEDULES\",\"databaseName\":\"shop\",\"includeArgs\":false}",
                json(Requests.listSchedules("shop", false)));
    }

    @Test
    public void test_collection_requests() {
        assertEquals("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"shop\",\"collectionName\":\"orders\"}",
                json(Requests.createCollection("shop", "orders")));
        assertEquals("{\"type\":\"GET_SCHEMA\",\"databaseName\":\"shop\",\"collectionName\":\"orders\"}",
                json(Requests.getSchema("shop", "orders")));
        assertEquals("{\"type\":\"CREATE_INDEX\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                + "\"fieldName\":\"customer\"}", json(Requests.createIndex("shop", "orders", "customer")));
        assertEquals(
                "{\"type\":\"SAVE_SCHEMA\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"schema\":{\"type\":\"object\"}}",
                json(Requests.saveSchema("shop", "orders", parse("{\"type\":\"object\"}"))));
    }

    @Test
    public void test_aggregate_page() {
        assertEquals("{\"type\":\"AGGREGATE\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                + "\"aggregationSteps\":[{\"type\":\"SKIP\",\"skip\":2000},{\"type\":\"LIMIT\",\"limit\":1000}]}",
                json(Requests.aggregatePage("shop", "orders", 2000, 1000)));
    }

    @Test
    public void test_bulk_save() {
        final var documents = new JsonArray();
        documents.add(parse("{\"_id\":\"o1\",\"at\":\"#datetime(2026-01-01T00:00:00)\"}"));
        assertEquals(
                "{\"type\":\"BULK_SAVE\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                        + "\"objects\":[{\"_id\":\"o1\",\"at\":\"#datetime(2026-01-01T00:00:00)\"}]}",
                json(Requests.bulkSave("shop", "orders", documents)));
    }

    @Test
    public void test_save_procedure_maps_source_to_script() {
        final var listed = parse("{\"name\":\"total\",\"source\":\"export default 1;\",\"sourceHash\":\"ab\","
                + "\"description\":\"sums\",\"enabled\":false,\"version\":4,\"createdAt\":1,\"updatedAt\":2,"
                + "\"updatedBy\":\"bob\"}");
        assertEquals(
                "{\"type\":\"SAVE_PROCEDURE\",\"databaseName\":\"shop\",\"name\":\"total\","
                        + "\"script\":\"export default 1;\",\"description\":\"sums\",\"enabled\":false}",
                json(Requests.saveProcedureFrom("shop", listed)));
    }

    @Test
    public void test_save_trigger_drops_version_definer_and_audit_fields() {
        final var listed = parse("{\"name\":\"audit\",\"events\":[\"CREATED\"],\"procedureName\":\"total\","
                + "\"mode\":\"document\",\"timing\":\"after\",\"allowCascade\":false,\"enabled\":true,"
                + "\"definer\":\"bob\",\"version\":3,\"createdAt\":1,\"updatedAt\":2,\"disabledReason\":null,"
                + "\"collectionName\":\"orders\"}");
        assertEquals("{\"type\":\"SAVE_TRIGGER\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                + "\"name\":\"audit\",\"events\":[\"CREATED\"],\"procedureName\":\"total\",\"mode\":\"document\","
                + "\"timing\":\"after\",\"allowCascade\":false,\"enabled\":true}",
                json(Requests.saveTriggerFrom("shop", listed)));
    }

    @Test
    public void test_save_schedule_keeps_args_and_cron_or_interval() {
        final var cron = parse("{\"name\":\"nightly\",\"procedureName\":\"total\",\"cron\":\"0 3 * * *\","
                + "\"intervalMs\":0,\"args\":{\"limit\":7},\"timeoutMs\":0,\"enabled\":true,\"definer\":\"bob\","
                + "\"version\":2,\"nextRunAt\":5,\"owner\":\"n1\"}");
        assertEquals("{\"type\":\"SAVE_SCHEDULE\",\"databaseName\":\"shop\",\"name\":\"nightly\","
                + "\"procedureName\":\"total\",\"cron\":\"0 3 * * *\",\"args\":{\"limit\":7},\"timeoutMs\":0,"
                + "\"enabled\":true}", json(Requests.saveScheduleFrom("shop", cron)));
        final var interval = parse("{\"name\":\"tick\",\"procedureName\":\"total\",\"intervalMs\":60000,"
                + "\"args\":{},\"timeoutMs\":500,\"description\":\"every minute\",\"enabled\":false}");
        assertEquals(
                "{\"type\":\"SAVE_SCHEDULE\",\"databaseName\":\"shop\",\"name\":\"tick\","
                        + "\"procedureName\":\"total\",\"intervalMs\":60000,\"args\":{},\"timeoutMs\":500,"
                        + "\"description\":\"every minute\",\"enabled\":false}",
                json(Requests.saveScheduleFrom("shop", interval)));
    }
}
