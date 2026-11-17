package io.github.christiancg.lwnrdb.spring.boot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.ReadMode;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.TransientDataAccessResourceException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TransactionalIT {
    private ServerProcess server;
    private ConfigurableApplicationContext context;
    private OrderService service;
    private OrderRepository orders;

    @BeforeAll
    public void start() {
        server = ServerProcess.start(Map.of("transactionIdleTimeoutMs", "2000"));
        SpringServer.createOrders(server);
        context = SpringServer.start(server, Map.of());
        service = context.getBean(OrderService.class);
        orders = context.getBean(OrderRepository.class);
    }

    @AfterAll
    public void stop() throws Exception {
        context.close();
        server.close();
    }

    @Test
    public void test_a_transactional_method_commits_both_writes_and_reads_its_own() {
        assertEquals(2, service.saveBothAndCount(Order.of("t1", "new", 1), Order.of("t2", "new", 2)));
        assertTrue(orders.findById("t1").isPresent());
        assertTrue(orders.findById("t2").isPresent());
    }

    @Test
    public void test_an_exception_rolls_back() {
        assertThrows(IllegalStateException.class, () -> service.saveThenFail(Order.of("r1", "new", 1)));
        assertTrue(orders.findById("r1").isEmpty());
    }

    @Test
    public void test_outside_readers_see_nothing_until_commit() throws Exception {
        final var plain = context.getBean(LwnrdbOperations.class).collection(Order.class);
        final var writer = new Thread(() -> {
            try {
                service.saveAndWait(Order.of("w1", "new", 1), 800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();
        Thread.sleep(300);
        assertTrue(plain.findById("w1", ReadMode.DIRTY).isEmpty());
        writer.join(10_000);
        assertTrue(orders.findById("w1").isPresent());
    }

    @Test
    public void test_an_idle_transaction_is_reported_as_a_transient_failure() {
        assertThrows(TransientDataAccessResourceException.class,
                () -> service.saveAndWait(Order.of("i1", "new", 1), 4000));
        assertTrue(orders.findById("i1").isEmpty());
    }
}
