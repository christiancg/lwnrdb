package io.github.christiancg.lwnrdb.spring.boot.it;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.admin.Permissions;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.testsupport.ServerProcess;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PermissionDeniedDataAccessException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExceptionTranslationIT {
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

    @Test
    public void test_server_refusals_become_spring_exceptions() {
        assertThrows(DuplicateKeyException.class,
                () -> orders.saveAll(List.of(Order.of("d", "new", 1), Order.of("d", "new", 2))));
        final var operations = context.getBean(LwnrdbOperations.class);
        operations.collection(Order.class).schema().save(Document.of("type", "object", "required", List.of("status"),
                "properties", Document.of("total", Document.of("type", "number", "maximum", 100))));
        try {
            assertThrows(DataIntegrityViolationException.class, () -> orders.save(Order.of("big", "new", 500)));
        } finally {
            operations.collection(Order.class).schema().delete();
        }
        operations.execute(client -> {
            client.admin().users().create("reader", "Reader-Secret-1234", Permissions.builder().build());
            return null;
        });
        try (var readerContext = SpringServer.start(server, Map.of("lwnrdb.username", "reader", "lwnrdb.password",
                "Reader-Secret-1234", "spring.main.lazy-initialization", "true"))) {
            final var readerOrders = readerContext.getBean(OrderRepository.class);
            assertThrows(PermissionDeniedDataAccessException.class, () -> readerOrders.save(Order.of("x", "new", 1)));
        }
    }
}
