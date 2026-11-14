package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.ReadMode;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.query.Filters;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class DataIT extends ServerIT {
    private Collection<Document> orders;

    @BeforeAll
    public void createCollection() {
        final var database = client.database("data_it");
        database.create();
        orders = database.collection("orders");
        orders.create();
    }

    @Test
    public void test_save_upserts_and_find_by_id_reads_back() {
        final var first = orders.save(Document.of("_id", "o-1", "status", "new", "total", 10));
        assertTrue(first.inserted());
        assertEquals("o-1", first.id());
        final var second = orders.save(Document.of("_id", "o-1", "status", "paid", "total", 12.5));
        assertFalse(second.inserted());
        final var read = orders.findById("o-1").orElseThrow();
        assertEquals("paid", read.getString("status").orElseThrow());
        assertEquals(12.5, read.getDouble("total").orElseThrow());
        assertEquals("paid", orders.findById("o-1", ReadMode.DIRTY).orElseThrow().getString("status").orElseThrow());
    }

    @Test
    public void test_a_save_without_id_gets_one_and_ids_are_case_sensitive() {
        final var generated = orders.save(Document.of("status", "new"));
        assertTrue(orders.findById(generated.id()).isPresent());
        orders.save(Document.of("_id", "Case", "v", 1));
        assertTrue(orders.findById("case").isEmpty());
        assertTrue(orders.findById("Case").isPresent());
    }

    @Test
    public void test_save_all_reports_inserts_and_updates_and_refuses_duplicates() {
        orders.save(Document.of("_id", "b-1", "v", 1));
        final var result = orders
                .saveAll(List.of(Document.of("_id", "b-1", "v", 2), Document.of("_id", "b-2", "v", 3)));
        assertEquals(List.of("b-1"), result.updated());
        assertEquals(List.of("b-2"), result.inserted());
        final var error = assertThrows(LwnrdbException.class,
                () -> orders.saveAll(List.of(Document.of("_id", "d-1"), Document.of("_id", "d-1"))));
        assertEquals("400-3", error.rawCode().orElseThrow());
    }

    @Test
    public void test_delete_removes_the_document_and_a_missing_one_is_reported() {
        orders.save(Document.of("_id", "gone", "v", 1));
        orders.delete("gone");
        assertTrue(orders.findById("gone").isEmpty());
        assertThrows(NotFoundException.class, () -> orders.delete("gone"));
    }

    @Test
    public void test_delete_where_removes_every_match_and_reports_the_count() {
        orders.saveAll(List.of(Document.of("_id", "dw-1", "status", "archived", "total", 10),
                Document.of("_id", "dw-2", "status", "archived", "total", 500),
                Document.of("_id", "dw-3", "status", "active", "total", 10)));
        assertEquals(1, orders
                .deleteWhere(Filters.and(Filters.field("status").eq("archived"), Filters.field("total").lt(100))));
        assertTrue(orders.findById("dw-1").isEmpty());
        assertTrue(orders.findById("dw-2").isPresent());
        assertTrue(orders.findById("dw-3").isPresent());
        assertEquals(0, orders.deleteWhere(Filters.field("status").eq("never")));
    }

    @Test
    public void test_nested_values_round_trip() {
        final var nested = Document.of("_id", "n-1", "customer",
                Document.of("name", "Ana", "tags", List.of("vip", "new")), "flags", List.of(true, false));
        orders.save(nested);
        final var read = orders.findById("n-1").orElseThrow();
        assertEquals("Ana", read.getString("customer.name").orElseThrow());
        assertEquals(List.of("vip", "new"), read.getList("customer.tags").orElseThrow());
    }
}
