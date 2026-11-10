package io.github.christiancg.lwnrdb.spring.boot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DerivedQueryIT {
    private ServerProcess server;
    private ConfigurableApplicationContext context;
    private OrderRepository orders;
    private LwnrdbOperations operations;

    @BeforeAll
    public void start() {
        server = ServerProcess.start(Map.of());
        SpringServer.createOrders(server);
        context = SpringServer.start(server, Map.of());
        orders = context.getBean(OrderRepository.class);
        operations = context.getBean(LwnrdbOperations.class);
        orders.saveAll(List.of(order("o1", "new", 10, 1, GeoPoint.of(40.7, -74.0)),
                order("o2", "paid", 20, 2, GeoPoint.of(40.8, -74.0)),
                order("o3", "paid", 30, 3, GeoPoint.of(-12.0, -77.0)),
                order("o4", "shipped", 40, 4, GeoPoint.of(-12.1, -77.1))));
    }

    @AfterAll
    public void stop() throws Exception {
        context.close();
        server.close();
    }

    private static Order order(String id, String status, double total, int day, GeoPoint location) {
        return new Order(id, status, total, LocalDateTime.of(2024, 1, day, 10, 0), location,
                Order.of(id, status, total).embedding(), null);
    }

    private static List<String> ids(List<Order> found) {
        return found.stream().map(Order::orderId).sorted().toList();
    }

    private List<String> withAndWithoutIndex(String field, Supplier<List<Order>> query) {
        final var scanned = ids(query.get());
        final var indexes = operations.collection(Order.class).indexes();
        indexes.create(field);
        try {
            assertEquals(scanned, ids(query.get()), "index and scan disagree on " + field);
        } finally {
            indexes.drop(field);
        }
        return scanned;
    }

    @Test
    public void test_comparison_keywords() {
        assertEquals(List.of("o2", "o3"), withAndWithoutIndex("status", () -> orders.findByStatus("paid")));
        assertEquals(List.of("o3"),
                withAndWithoutIndex("total", () -> orders.findByStatusAndTotalGreaterThan("paid", 25)));
        assertEquals(List.of("o1", "o2", "o3"), ids(orders.findByStatusOrTotalLessThan("paid", 15)));
        assertEquals(List.of("o1", "o4"), ids(orders.findByStatusNot("paid")));
        assertEquals(List.of("o3", "o4"), withAndWithoutIndex("total", () -> orders.findByTotalGreaterThanEqual(30)));
        assertEquals(List.of("o1", "o2"), ids(orders.findByTotalLessThanEqual(20)));
        assertEquals(List.of("o2", "o3"), withAndWithoutIndex("total", () -> orders.findByTotalBetween(15, 35)));
        assertEquals(List.of("o1", "o4"), ids(orders.findByStatusIn(List.of("new", "shipped"))));
        assertEquals(List.of("o1", "o4"), ids(orders.findByStatusNotIn(List.of("paid"))));
        assertEquals(List.of("o4"), ids(orders.findByStatusContaining("hip")));
        assertEquals(List.of("o3", "o4"),
                withAndWithoutIndex("placedAt", () -> orders.findByPlacedAtAfter(LocalDateTime.of(2024, 1, 2, 12, 0))));
        assertEquals(List.of("o1"), ids(orders.findByPlacedAtBefore(LocalDateTime.of(2024, 1, 2, 0, 0))));
        assertTrue(orders.findByStatusIsNull().isEmpty());
        assertEquals("o2", orders.findByOrderId("o2").orElseThrow().orderId());
        assertTrue(orders.findByOrderId("nope").isEmpty());
    }

    @Test
    public void test_geo_within() {
        final var southAmerica = List.of(GeoPoint.of(-20, -80), GeoPoint.of(-5, -80), GeoPoint.of(-5, -70),
                GeoPoint.of(-20, -70));
        assertEquals(List.of("o3", "o4"), ids(orders.findByLocationWithin(southAmerica)));
    }

    @Test
    public void test_limits_sorting_pages_slices_and_streams() {
        assertEquals("o3", orders.findFirstByStatusOrderByTotalDesc("paid").orderId());
        assertEquals(List.of("o1", "o2"), orders.findTop2ByOrderByTotalAsc().stream().map(Order::orderId).toList());
        assertEquals(List.of("o3", "o2"),
                orders.findByStatus("paid", Sort.by("total").descending()).stream().map(Order::orderId).toList());
        assertEquals(1, orders.findByStatus("paid", Limit.of(1)).size());
        final var page = orders.findByStatus("paid", PageRequest.of(0, 1, Sort.by("total")));
        assertEquals(2, page.getTotalElements());
        assertEquals("o2", page.getContent().get(0).orderId());
        final var slice = orders.findSliceByStatus("paid", PageRequest.of(0, 1, Sort.by("total")));
        assertTrue(slice.hasNext());
        assertFalse(orders.findSliceByStatus("paid", PageRequest.of(1, 1, Sort.by("total"))).hasNext());
        try (var stream = orders.streamByStatus("paid")) {
            assertEquals(2, stream.count());
        }
    }

    @Test
    public void test_count_exists_and_delete_prefixes() {
        assertEquals(2, orders.countByStatus("paid"));
        assertTrue(orders.existsByStatus("new"));
        assertFalse(orders.existsByStatus("lost"));
        orders.save(order("gone", "doomed", 1, 5, GeoPoint.of(0, 0)));
        assertEquals(1, orders.deleteByStatus("doomed"));
        assertTrue(orders.findById("gone").isEmpty());
    }
}
