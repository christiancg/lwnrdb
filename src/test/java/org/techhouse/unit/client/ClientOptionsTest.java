package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientMode;
import org.techhouse.client.ClientOptions;
import org.techhouse.client.ClientOptions.CollectionRef;
import org.techhouse.config.ConfigKey;

public class ClientOptionsTest {
    private static ClientOptions parse(String... args) {
        return ClientOptions.parse(args, Map.of());
    }

    private static void assertUsageError(String... args) {
        final var error = assertThrows(ClientException.class, () -> parse(args));
        assertEquals(ClientException.USAGE, error.exitCode());
    }

    @Test
    public void test_each_mode_parses() {
        assertEquals(ClientMode.SHELL, parse("--shell").mode());
        assertEquals(ClientMode.EXEC, parse("--exec", "{}").mode());
        assertEquals(ClientMode.DUMP, parse("--dump").mode());
        assertEquals(ClientMode.RESTORE, parse("--restore").mode());
    }

    @Test
    public void test_defaults() {
        final var options = parse("--dump");
        assertEquals(ClientOptions.DEFAULT_HOST, options.host());
        assertEquals(Integer.parseInt(ConfigKey.PORT.defaultValue()), options.port());
        assertEquals(ClientOptions.DEFAULT_USER, options.user());
        assertNull(options.password());
        assertFalse(options.tls());
        assertFalse(options.insecure());
        assertFalse(options.compress());
        assertTrue(options.commands().isEmpty());
        assertTrue(options.databases().isEmpty());
        assertTrue(options.collections().isEmpty());
    }

    @Test
    public void test_connection_flags_parse() {
        final var options = parse("--shell", "--host", "db.example", "--port", "9000", "--user", "bob", "--tls",
                "--insecure");
        assertEquals("db.example", options.host());
        assertEquals(9000, options.port());
        assertEquals("bob", options.user());
        assertTrue(options.tls());
        assertTrue(options.insecure());
    }

    @Test
    public void test_exec_values_accumulate_and_a_bare_exec_reads_stdin() {
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), parse("--exec", "{\"a\":1}", "--exec", "{\"b\":2}").commands());
        assertTrue(parse("--exec", "--port", "1").commands().isEmpty());
        assertTrue(parse("--exec").commands().isEmpty());
    }

    @Test
    public void test_env_password_used_when_no_flag() {
        final var options = ClientOptions.parse(new String[]{"--restore"},
                Map.of(ClientOptions.PASSWORD_ENV, "fromEnv"));
        assertEquals("fromEnv", options.password());
    }

    @Test
    public void test_flag_password_beats_env() {
        final var options = ClientOptions.parse(new String[]{"--restore", "--password", "fromFlag"},
                Map.of(ClientOptions.PASSWORD_ENV, "fromEnv"));
        assertEquals("fromFlag", options.password());
    }

    @Test
    public void test_two_modes_rejected() {
        assertUsageError("--shell", "--dump");
        assertUsageError("--exec", "{}", "--restore");
        assertUsageError("--dump", "--dump");
    }

    @Test
    public void test_no_mode_rejected() {
        assertUsageError("--host", "localhost");
    }

    @Test
    public void test_unknown_flag_rejected() {
        assertUsageError("--dump", "--everything");
        assertUsageError("--dump", "stray");
    }

    @Test
    public void test_missing_value_rejected() {
        assertUsageError("--dump", "--host");
        assertUsageError("--dump", "--user", "--compress");
    }

    @Test
    public void test_bad_port_rejected() {
        assertUsageError("--dump", "--port", "http");
        assertUsageError("--dump", "--port", "0");
        assertUsageError("--dump", "--port", "65536");
        assertUsageError("--dump", "--port", "123456");
    }

    @Test
    public void test_databases_list_split() {
        assertEquals(List.of("shop", "crm"), parse("--dump", "--databases", "shop, crm,shop").databases());
        assertUsageError("--dump", "--databases", "shop,,crm");
    }

    @Test
    public void test_insecure_without_tls_rejected() {
        assertUsageError("--shell", "--insecure");
    }

    @Test
    public void test_collections_parse_into_refs() {
        final var options = parse("--dump", "--collections", "shop.orders,crm.leads,shop.orders", "--compress");
        assertEquals(List.of(new CollectionRef("shop", "orders"), new CollectionRef("crm", "leads")),
                options.collections());
        assertTrue(options.compress());
    }

    @Test
    public void test_collection_item_without_a_dot_rejected() {
        assertUsageError("--dump", "--collections", "orders");
        assertUsageError("--dump", "--collections", "shop.");
    }

    @Test
    public void test_collection_item_with_two_dots_rejected() {
        assertUsageError("--dump", "--collections", "shop.orders.x");
    }

    @Test
    public void test_databases_and_collections_together_rejected() {
        assertUsageError("--dump", "--databases", "shop", "--collections", "crm.leads");
    }

    @Test
    public void test_filters_outside_dump_rejected() {
        assertUsageError("--restore", "--databases", "shop");
        assertUsageError("--shell", "--collections", "shop.orders");
        assertUsageError("--exec", "{}", "--compress");
    }
}
