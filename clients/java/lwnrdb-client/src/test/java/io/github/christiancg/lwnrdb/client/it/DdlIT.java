package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.error.ConflictException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DdlIT extends ServerIT {
    @Test
    public void test_databases_and_collections_are_created_listed_and_dropped() {
        final var database = client.database("ddl_it");
        database.create();
        assertTrue(client.listDatabases().contains("ddl_it"));
        final var orders = database.collection("orders");
        orders.create();
        assertEquals(List.of("orders"), database.listCollections());
        orders.drop();
        assertTrue(database.listCollections().isEmpty());
        database.drop();
        assertFalse(client.listDatabases().contains("ddl_it"));
    }

    @Test
    public void test_names_differing_only_by_case_and_reserved_names_are_refused() {
        client.database("Case_db").create();
        final var error = assertThrows(ConflictException.class, () -> client.database("case_DB").create());
        assertEquals("409-11", error.rawCode().orElseThrow());
        assertThrows(LwnrdbException.class, () -> client.database("Admin").create());
        assertThrows(IllegalArgumentException.class, () -> client.database("no"));
    }

    @Test
    public void test_indexes_create_many_recreate_drop_and_reindex() {
        final var database = client.database("index_it");
        database.create();
        final var users = database.collection("users");
        users.create();
        users.saveAll(List.of(Document.of("_id", "u1", "email", "a@x", "status", "on"),
                Document.of("_id", "u2", "email", "b@x", "status", "off")));
        users.indexes().create("email", "status");
        users.indexes().create("email");
        assertEquals(1, users.query().filter(field("email").eq("A@X")).count());
        assertEquals(List.of("email"), users.indexes().reindex("email").rebuiltFields());
        assertEquals(2, users.indexes().reindex().rebuiltFields().size());
        final var primary = users.indexes().reindexPrimaryKey();
        assertEquals(2, primary.primaryKeyEntries().getAsLong());
        users.indexes().drop("status");
        users.indexes().drop("status");
        assertEquals(1, users.query().filter(field("status").eq("on")).count());
    }

    @Test
    public void test_a_schema_is_enforced_until_deleted() {
        final var database = client.database("schema_it");
        database.create();
        final var people = database.collection("people");
        people.create();
        assertTrue(people.schema().get().isEmpty());
        people.schema().save(Document.of("type", "object", "required", List.of("name"), "properties",
                Document.of("name", Document.of("type", "string", "minLength", 1))));
        assertEquals("object", people.schema().get().orElseThrow().getString("type").orElseThrow());
        final var refused = assertThrows(LwnrdbException.class, () -> people.save(Document.of("_id", "p1")));
        assertEquals("400-7", refused.rawCode().orElseThrow());
        people.save(Document.of("_id", "p1", "name", "Ana"));
        people.schema().delete();
        people.save(Document.of("_id", "p2"));
        assertTrue(people.findById("p2").isPresent());
    }
}
