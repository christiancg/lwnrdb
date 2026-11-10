package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.ReadMode;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TransactionIT extends ServerIT {
    private Collection<Document> accounts;

    @Override
    protected Map<String, String> config() {
        return Map.of("transactionIdleTimeoutMs", "4000");
    }

    @BeforeAll
    public void createCollections() {
        final var database = client.database("tx_it");
        database.create();
        database.collection("accounts").create();
        database.collection("ledger").create();
        accounts = database.collection("accounts");
    }

    @Test
    public void test_a_commit_is_visible_to_others_and_reads_see_own_writes() {
        final var seen = client.transaction(transaction -> {
            final var inside = transaction.collection("tx_it", "accounts");
            inside.save(Document.of("_id", "a1", "balance", 100));
            inside.saveAll(List.of(Document.of("_id", "a2", "balance", 50)));
            assertTrue(accounts.findById("a1", ReadMode.DIRTY).isEmpty());
            assertEquals(1, inside.query().filter(field("_id").eq("a2")).count());
            return inside.findById("a1").orElseThrow().getLong("balance").orElseThrow();
        });
        assertEquals(100, seen);
        assertTrue(accounts.findById("a1").isPresent());
        assertTrue(accounts.findById("a2").isPresent());
    }

    @Test
    public void test_a_rollback_and_a_failing_body_leave_nothing() {
        try (var transaction = client.beginTransaction()) {
            transaction.collection("tx_it", "accounts").save(Document.of("_id", "r1"));
            transaction.rollback();
        }
        assertTrue(accounts.findById("r1").isEmpty());
        assertThrows(IllegalStateException.class, () -> client.transaction(transaction -> {
            transaction.collection("tx_it", "accounts").save(Document.of("_id", "r2"));
            throw new IllegalStateException("abort");
        }));
        assertTrue(accounts.findById("r2").isEmpty());
    }

    @Test
    public void test_an_idle_transaction_is_rolled_back_by_the_server() throws Exception {
        try (var transaction = client.beginTransaction()) {
            final var inside = transaction.collection("tx_it", "accounts");
            inside.save(Document.of("_id", "idle"));
            Thread.sleep(6000);
            final var error = assertThrows(LwnrdbException.class, () -> inside.save(Document.of("_id", "idle2")));
            assertEquals("409-16", error.rawCode().orElseThrow());
        } catch (LwnrdbException ignored) {
        }
        assertTrue(accounts.findById("idle").isEmpty());
    }

    @Test
    public void test_transactions_on_different_collections_run_concurrently() throws Exception {
        try (var other = LwnrdbClient.connect(ItSettings.of(server).build())) {
            final var first = new FutureTask<>(() -> client.transaction(
                    transaction -> transaction.collection("tx_it", "accounts").save(Document.of("_id", "c1"))));
            final var second = new FutureTask<>(() -> other.transaction(
                    transaction -> transaction.collection("tx_it", "ledger").save(Document.of("_id", "l1"))));
            new Thread(first, "tx-it-accounts").start();
            new Thread(second, "tx-it-ledger").start();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertTrue(accounts.findById("c1").isPresent());
    }

    @Test
    public void test_a_second_begin_on_another_connection_is_independent() {
        try (var first = client.beginTransaction(); var second = client.beginTransaction()) {
            first.collection("tx_it", "accounts").save(Document.of("_id", "s1"));
            second.rollback();
            first.commit();
        }
        assertTrue(accounts.findById("s1").isPresent());
    }
}
