package io.github.christiancg.lwnrdb.spring.data.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.christiancg.lwnrdb.spring.data.Stack;
import io.github.christiancg.lwnrdb.spring.data.model.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class LwnrdbTransactionManagerTest {
    private Stack stack;
    private LwnrdbTransactionManager manager;
    private TransactionTemplate transactions;

    @BeforeEach
    public void start() throws Exception {
        stack = new Stack();
        stack.replies.reply("START_TRANSACTION",
                "{\"type\":\"START_TRANSACTION\",\"status\":\"OK\",\"transactionId\":" + "\"t1\"}");
        manager = new LwnrdbTransactionManager(stack.client);
        transactions = new TransactionTemplate(manager);
    }

    @AfterEach
    public void stop() throws Exception {
        stack.close();
    }

    private List<String> typesUntil(String last) throws InterruptedException {
        final var types = new ArrayList<String>();
        String type;
        do {
            type = stack.server.nextRequest().get("type").asString();
            types.add(type);
        } while (!type.equals(last));
        return types;
    }

    @Test
    public void test_a_transaction_commits_on_one_connection_and_unbinds() throws Exception {
        transactions.executeWithoutResult(status -> stack.template.save(Widget.of("w1", "a", 1)));
        assertEquals(List.of("START_TRANSACTION", "SAVE", "COMMIT_TRANSACTION"), typesUntil("COMMIT_TRANSACTION"));
        assertEquals(1, stack.server.acceptedConnections());
        assertNull(TransactionSynchronizationManager.getResource(stack.client));
        assertSame(stack.client, manager.getResourceFactory());
    }

    @Test
    public void test_every_template_operation_runs_inside_the_bound_transaction() throws Exception {
        stack.results("[{\"_id\":\"w1\",\"name\":\"a\",\"size\":1}]");
        transactions.executeWithoutResult(status -> {
            stack.template.saveAll(List.of(Widget.of("w1", "a", 1)));
            stack.template.findById("w1", Widget.class);
            stack.template.deleteById("w1", Widget.class);
            stack.template.findAll(Widget.class);
            stack.template.updateWhere(Widget.class, null, Map.of("size", 3));
            stack.template.insert(Widget.of("w2", "b", 2));
        });
        assertEquals(List.of("START_TRANSACTION", "BULK_SAVE", "FIND_BY_ID", "DELETE", "AGGREGATE", "SAVE", "SAVE",
                "COMMIT_TRANSACTION"), typesUntil("COMMIT_TRANSACTION"));
        assertEquals(1, stack.server.acceptedConnections());
    }

    @Test
    public void test_an_exception_and_rollback_only_roll_back() throws Exception {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            stack.template.save(Widget.of("w1", "a", 1));
            throw new IllegalStateException("abort");
        }));
        typesUntil("ROLLBACK_TRANSACTION");
        transactions.executeWithoutResult(TransactionStatus::setRollbackOnly);
        typesUntil("ROLLBACK_TRANSACTION");
    }

    @Test
    public void test_requires_new_suspends_and_nested_is_refused() {
        final var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.executeWithoutResult(
                outer -> inner.executeWithoutResult(status -> stack.template.save(Widget.of("w2", "b", 2))));
        assertEquals(2, stack.server.acceptedConnections());
        final var nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThrows(NestedTransactionNotSupportedException.class,
                () -> transactions.executeWithoutResult(outer -> nested.executeWithoutResult(status -> {
                })));
    }

    @Test
    public void test_failures_to_start_commit_and_roll_back() {
        stack.replies.reply("COMMIT_TRANSACTION",
                "{\"type\":\"COMMIT_TRANSACTION\",\"status\":\"ERROR\"," + "\"errorCode\":\"500-33\"}");
        assertThrows(TransactionSystemException.class, () -> transactions.executeWithoutResult(status -> {
        }));
        stack.replies.forget("COMMIT_TRANSACTION");
        stack.replies.reply("ROLLBACK_TRANSACTION",
                "{\"type\":\"ROLLBACK_TRANSACTION\",\"status\":\"ERROR\"," + "\"errorCode\":\"409-4\"}");
        transactions.executeWithoutResult(TransactionStatus::setRollbackOnly);
        stack.replies.reply("ROLLBACK_TRANSACTION",
                "{\"type\":\"ROLLBACK_TRANSACTION\",\"status\":\"ERROR\"," + "\"errorCode\":\"409-1\"}");
        assertThrows(TransactionSystemException.class,
                () -> transactions.executeWithoutResult(TransactionStatus::setRollbackOnly));
        stack.replies.reply("START_TRANSACTION",
                "{\"type\":\"START_TRANSACTION\",\"status\":\"ERROR\"," + "\"errorCode\":\"409-3\"}");
        assertThrows(CannotCreateTransactionException.class, () -> transactions.executeWithoutResult(status -> {
        }));
    }
}
