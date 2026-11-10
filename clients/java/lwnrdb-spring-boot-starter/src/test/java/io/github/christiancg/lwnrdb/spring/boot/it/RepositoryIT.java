package io.github.christiancg.lwnrdb.spring.boot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RepositoryIT {
    private ServerProcess server;
    private ConfigurableApplicationContext context;
    private OrderRepository orders;

    @BeforeAll
    public void start() {
        server = ServerProcess.start(Map.of());
        SpringServer.createOrders(server);
        context = SpringServer.start(server, Map.of());
        orders = context.getBean(OrderRepository.class);
    }

    @AfterAll
    public void stop() throws Exception {
        context.close();
        server.close();
    }

    @BeforeEach
    public void empty() {
        orders.deleteAll();
    }

    @Test
    public void test_crud_round_trips_and_generates_ids() {
        final var saved = orders.save(Order.of(null, "new", 10).withNote("not stored"));
        assertNotNull(saved.orderId());
        final var read = orders.findById(saved.orderId()).orElseThrow();
        assertEquals("new", read.status());
        assertEquals(saved.placedAt(), read.placedAt());
        assertEquals(saved.location(), read.location());
        assertEquals(saved.embedding(), read.embedding());
        assertNull(read.note());
        assertTrue(orders.existsById(saved.orderId()));
        assertEquals(1, orders.count());
        orders.saveAll(List.of(Order.of("a", "paid", 20), Order.of("b", "paid", 30)));
        assertEquals(2, orders.findAllById(List.of("a", "b", "missing")).size());
        assertEquals(3, orders.findAll().size());
        orders.deleteById("a");
        orders.deleteById("a");
        orders.delete(orders.findById("b").orElseThrow());
        assertEquals(1, orders.count());
        final var raw = context.getBean(LwnrdbOperations.class).collection(Order.class).query().toDocuments();
        assertEquals(saved.orderId(), raw.get(0).id().orElseThrow());
        assertFalse(raw.get(0).has("orderId"));
        assertFalse(raw.get(0).has("note"));
    }

    @Test
    public void test_paging_and_sorting() {
        for (var i = 0; i < 5; i++) {
            orders.save(Order.of("p" + i, "paid", i * 10));
        }
        final var page = orders.findAll(PageRequest.of(1, 2, Sort.by("total").descending()));
        assertEquals(5, page.getTotalElements());
        assertEquals(List.of("p2", "p1"), page.getContent().stream().map(Order::orderId).toList());
        assertEquals(List.of("p0", "p1", "p2", "p3", "p4"),
                orders.findAll(Sort.by("total")).stream().map(Order::orderId).toList());
        assertThrows(InvalidDataAccessApiUsageException.class, () -> orders.findAll(Sort.by("total", "status")));
    }
}
