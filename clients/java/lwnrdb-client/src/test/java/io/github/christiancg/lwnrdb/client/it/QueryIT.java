package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.it.IndexComparison.ids;
import static io.github.christiancg.lwnrdb.client.it.IndexComparison.sameWithAndWithoutIndex;
import static io.github.christiancg.lwnrdb.client.it.IndexComparison.sortedIds;
import static io.github.christiancg.lwnrdb.client.query.Filters.and;
import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static io.github.christiancg.lwnrdb.client.query.Filters.nand;
import static io.github.christiancg.lwnrdb.client.query.Filters.nor;
import static io.github.christiancg.lwnrdb.client.query.Filters.or;
import static io.github.christiancg.lwnrdb.client.query.Filters.script;
import static io.github.christiancg.lwnrdb.client.query.Filters.xor;
import static io.github.christiancg.lwnrdb.client.query.MapOps.remove;
import static io.github.christiancg.lwnrdb.client.query.MapOps.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.query.CastType;
import io.github.christiancg.lwnrdb.client.query.MidOps;
import io.github.christiancg.lwnrdb.client.query.Order;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class QueryIT extends ServerIT {
    private Collection<Document> orders;

    @BeforeAll
    public void seed() {
        final var database = client.database("query_it");
        database.create();
        orders = database.collection("orders");
        final var customers = database.collection("customers");
        orders.create();
        customers.create();
        final var rows = new ArrayList<Document>();
        for (var i = 0; i < 20; i++) {
            rows.add(Document.of("_id", "o-" + (i < 10 ? "0" : "") + i, "status", i % 3 == 0 ? "new" : "paid", "total",
                    i * 10, "qty", i % 4 + 1, "price", 2.5, "customerId", "c-" + i % 2, "name", "Item " + i, "tags",
                    List.of("t" + i % 2)));
        }
        orders.saveAll(rows);
        customers.saveAll(
                List.of(Document.of("_id", "c-0", "city", "Lima"), Document.of("_id", "c-1", "city", "Quito")));
    }

    @Test
    public void test_field_operators_answer_the_same_with_and_without_an_index() {
        sameWithAndWithoutIndex(orders, "total",
                () -> sortedIds(orders.query().filter(field("total").gt(150)).toList()));
        assertEquals(List.of("o-16", "o-17", "o-18", "o-19"), sameWithAndWithoutIndex(orders, "total",
                () -> sortedIds(orders.query().filter(field("total").gte(160)).toList())));
        assertEquals(2,
                sameWithAndWithoutIndex(orders, "total", () -> orders.query().filter(field("total").lt(20)).count()));
        assertEquals(3,
                sameWithAndWithoutIndex(orders, "total", () -> orders.query().filter(field("total").lte(20)).count()));
        assertEquals(7, sameWithAndWithoutIndex(orders, "status",
                () -> orders.query().filter(field("status").eq("NEW")).count()));
        assertEquals(13, sameWithAndWithoutIndex(orders, "status",
                () -> orders.query().filter(field("status").ne("new")).count()));
        assertEquals(20, sameWithAndWithoutIndex(orders, "status",
                () -> orders.query().filter(field("status").in("new", "paid")).count()));
        assertEquals(13, sameWithAndWithoutIndex(orders, "status",
                () -> orders.query().filter(field("status").notIn("new")).count()));
        assertEquals(11, orders.query().filter(field("name").contains("1")).count());
        assertEquals(10, orders.query().filter(field("tags").contains("t1")).count());
    }

    @Test
    public void test_conjunctions_and_scripts() {
        final var isNew = field("status").eq("new");
        final var big = field("total").gte(100);
        assertEquals(3, orders.query().filter(and(isNew, big)).count());
        assertEquals(14, orders.query().filter(or(isNew, big)).count());
        assertEquals(6, orders.query().filter(nor(isNew, big)).count());
        assertEquals(11, orders.query().filter(xor(isNew, big)).count());
        assertEquals(17, orders.query().filter(nand(isNew, big)).count());
        assertEquals(4, orders.query().filter(script("export default (doc) => doc.total > 150;")).count());
    }

    @Test
    public void test_sort_skip_limit_and_first() {
        final var page = orders.query().sort("total", Order.DESC).skip(2).limit(3).toList();
        assertEquals(List.of("o-17", "o-16", "o-15"), ids(page));
        assertEquals(List.of("o-00", "o-01"), sameWithAndWithoutIndex(orders, "total",
                () -> ids(orders.query().sort("total", Order.ASC).limit(2).toList())));
        assertTrue(orders.query().filter(field("total").gt(1000)).first().isEmpty());
        assertTrue(orders.query().filter(field("total").gt(1000)).toList().isEmpty());
    }

    @Test
    public void test_group_by_distinct_join_and_map() {
        final var groups = orders.query().groupBy("status").sort("status", Order.ASC).toDocuments();
        assertEquals(2, groups.size());
        assertEquals("new", groups.get(0).getString("status").orElseThrow());
        assertEquals(7, groups.get(0).getList("group").orElseThrow().size());
        assertEquals(2, orders.query().distinct("status").toDocuments().size());
        final var joined = orders.query().filter(field("_id").eq("o-03"))
                .join("customers", "customerId", "_id", "customer").toList().get(0);
        assertEquals("Quito", ((java.util.Map<?, ?>) joined.getList("customer").orElseThrow().get(0)).get("city"));
        final var mapped = orders.query().filter(field("_id").eq("o-04"))
                .map(set("line", MidOps.multiply("qty", "price")),
                        set("label", MidOps.concat("name", MidOps.literal(" / "), "status")),
                        set("text", MidOps.cast("total", CastType.STRING)),
                        set("flag", MidOps.sum(1)).when(field("status").eq("paid")), remove("tags"))
                .toList().get(0);
        assertEquals(2.5, mapped.getDouble("line").getAsDouble());
        assertEquals("Item 4 / paid", mapped.getString("label").orElseThrow());
        assertEquals("40", mapped.getString("text").orElseThrow());
        assertEquals(1, mapped.getLong("flag").getAsLong());
        assertFalse(mapped.has("tags"));
    }

    @Test
    public void test_reduce_analyze_and_dirty_reads() {
        final var sum = orders.query().reduce("export default (acc, doc) => acc + doc.total;", 0, "sum").toDocuments();
        assertEquals(1900, sum.get(0).getLong("sum").getAsLong());
        final var analyzed = orders.query().filter(field("status").eq("new")).analyze();
        assertEquals(7, analyzed.results().size());
        assertFalse(analyzed.analysis().fieldNames().isEmpty());
        assertEquals(7, orders.query().filter(field("status").eq("new")).dirtyRead().count());
    }
}
