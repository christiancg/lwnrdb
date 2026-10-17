package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Client;
import org.techhouse.data.Transaction;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ClientTrackerTest {
    @Test
    public void test_add_client_under_max_connections() throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", 10);
        ClientTracker clientTracker = new ClientTracker();
        Socket mockSocket = Mockito.mock(Socket.class);
        InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
        Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0.1");

        UUID clientId = clientTracker.addClient(mockSocket);

        assertNotNull(clientId);
    }

    @Test
    public void test_add_client_max_connections_reached() throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", 1);
        ClientTracker clientTracker = new ClientTracker();
        Socket mockSocket1 = Mockito.mock(Socket.class);
        InetAddress mockInetAddress1 = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket1.getInetAddress()).thenReturn(mockInetAddress1);
        Mockito.when(mockInetAddress1.getHostAddress()).thenReturn("127.0.0.1");
        clientTracker.addClient(mockSocket1);

        Socket mockSocket2 = Mockito.mock(Socket.class);
        InetAddress mockInetAddress2 = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket2.getInetAddress()).thenReturn(mockInetAddress2);
        Mockito.when(mockInetAddress2.getHostAddress()).thenReturn("192.168.0.1");

        UUID clientId = clientTracker.addClient(mockSocket2);

        assertNull(clientId);
    }

    @Test
    public void test_add_client_unlimited_when_max_connections_zero()
            throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        for (int i = 0; i < 50; i++) {
            Socket mockSocket = Mockito.mock(Socket.class);
            InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
            Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
            Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0." + i);
            assertNotNull(clientTracker.addClient(mockSocket));
        }
    }

    @Test
    public void test_remove_client_with_valid_uuid() throws NoSuchFieldException, IllegalAccessException {
        ClientTracker clientTracker = new ClientTracker();
        UUID clientId = UUID.randomUUID();
        Socket mockSocket = Mockito.mock(Socket.class);
        InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
        Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0.1");
        clientTracker.addClient(mockSocket);
        clientTracker.removeById(clientId);
        final var clientsType = new ReflectionUtils.TypeToken<Map<UUID, Client>>() {
        };
        final var clients = TestUtils.getPrivateField(clientTracker, "clients", clientsType);
        assertNull(clients.get(clientId));
    }

    @Test
    public void test_remove_client_from_empty_map() throws NoSuchFieldException, IllegalAccessException {
        ClientTracker clientTracker = new ClientTracker();
        final var clientsType = new ReflectionUtils.TypeToken<Map<UUID, Client>>() {
        };
        final var clients = TestUtils.getPrivateField(clientTracker, "clients", clientsType);
        UUID clientId = UUID.randomUUID();
        clientTracker.removeById(clientId);
        assertTrue(clients.isEmpty());
    }

    @Test
    public void test_update_last_command_time_for_existing_client()
            throws NoSuchFieldException, IllegalAccessException {
        ClientTracker clientTracker = new ClientTracker();
        UUID clientId = UUID.randomUUID();
        Client client = new Client("127.0.0.1");
        final var clientsType = new ReflectionUtils.TypeToken<Map<UUID, Client>>() {
        };
        final var clients = TestUtils.getPrivateField(clientTracker, "clients", clientsType);
        clients.put(clientId, client);

        clientTracker.updateLastCommandTime(clientId);

        assertNotNull(client.getLastCommandTime());
    }

    @Test
    public void test_handle_null_client_id_gracefully() {
        ClientTracker clientTracker = new ClientTracker();

        assertDoesNotThrow(() -> clientTracker.updateLastCommandTime(null));
    }

    @Test
    public void test_set_authenticated_user_stores_username() throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", 10);
        ClientTracker clientTracker = new ClientTracker();
        Socket mockSocket = Mockito.mock(Socket.class);
        InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
        Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0.1");

        UUID clientId = clientTracker.addClient(mockSocket);
        clientTracker.setAuthenticatedUser(clientId, "Alice");

        assertEquals("Alice", clientTracker.getAuthenticatedUsername(clientId));
    }

    @Test
    public void test_get_authenticated_username_returns_null_when_not_set()
            throws NoSuchFieldException, IllegalAccessException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", 10);
        ClientTracker clientTracker = new ClientTracker();
        Socket mockSocket = Mockito.mock(Socket.class);
        InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
        Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0.1");

        UUID clientId = clientTracker.addClient(mockSocket);

        assertNull(clientTracker.getAuthenticatedUsername(clientId));
    }

    @Test
    public void test_get_authenticated_username_returns_null_for_unknown_id() {
        ClientTracker clientTracker = new ClientTracker();
        assertNull(clientTracker.getAuthenticatedUsername(UUID.randomUUID()));
    }

    @Test
    public void test_set_authenticated_user_null_client_id_no_throw() {
        ClientTracker clientTracker = new ClientTracker();
        assertDoesNotThrow(() -> clientTracker.setAuthenticatedUser(null, "Alice"));
    }

    @Test
    public void test_get_authenticated_username_null_client_id() {
        ClientTracker clientTracker = new ClientTracker();
        assertNull(clientTracker.getAuthenticatedUsername(null));
    }

    @Test
    public void test_has_active_transaction_finds_a_live_session_by_its_id() {
        ClientTracker clientTracker = new ClientTracker();
        final var clientId = clientTracker.registerForwardedClient("tx-owner");
        final var transactionId = UUID.randomUUID();
        clientTracker.setActiveTransaction(clientId, new Transaction(transactionId, clientId));

        assertTrue(clientTracker.hasActiveTransaction(transactionId.toString()));
        assertFalse(clientTracker.hasActiveTransaction(UUID.randomUUID().toString()));
        assertFalse(clientTracker.hasActiveTransaction(null));

        clientTracker.clearActiveTransaction(clientId);
        assertFalse(clientTracker.hasActiveTransaction(transactionId.toString()));
    }

    private static UUID connect(ClientTracker clientTracker, String username) {
        Socket socket = Mockito.mock(Socket.class);
        InetAddress address = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(address);
        Mockito.when(address.getHostAddress()).thenReturn("127.0.0.1");
        UUID clientId = clientTracker.addClient(socket);
        clientTracker.setAuthenticatedUser(clientId, username);
        return clientId;
    }

    @Test
    public void test_deauthenticate_user_clears_every_connection_of_that_name()
            throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        UUID first = connect(clientTracker, "carol");
        UUID second = connect(clientTracker, "carol");

        clientTracker.deauthenticateUser("carol");

        assertNull(clientTracker.getAuthenticatedUsername(first));
        assertNull(clientTracker.getAuthenticatedUsername(second));
    }

    @Test
    public void test_deauthenticate_user_leaves_other_names_alone()
            throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        UUID other = connect(clientTracker, "dave");
        connect(clientTracker, "carol");

        clientTracker.deauthenticateUser("carol");

        assertEquals("dave", clientTracker.getAuthenticatedUsername(other));
    }

    @Test
    public void test_deauthenticate_user_leaves_tx_sessions_and_forwarded_clients_alone() {
        ClientTracker clientTracker = new ClientTracker();
        UUID forwarded = clientTracker.registerForwardedClient("carol");
        var session = clientTracker.registerTxSession("session-1", "carol", "edge");
        try {
            clientTracker.deauthenticateUser("carol");

            assertEquals("carol", clientTracker.getAuthenticatedUsername(forwarded),
                    "a forwarded request acts for an edge connection that is deauthenticated on its own node");
            assertEquals("carol", clientTracker.getAuthenticatedUsername(session.clientId()),
                    "a participant slice must keep the identity that owns its thread-held locks");
        } finally {
            clientTracker.removeTxSession("session-1");
        }
    }

    @Test
    public void test_transaction_write_holders_union_across_collections() {
        final var clientTracker = new ClientTracker();
        final var clientId = clientTracker.registerForwardedClient("u");
        clientTracker.recordTransactionWrite(clientId, "db|a", "local");
        clientTracker.recordTransactionWrite(clientId, "db|b", "10.0.0.2:9000");
        clientTracker.recordTransactionWrite(clientId, "db|b", "10.0.0.3:9000");

        assertEquals(Set.of("local", "10.0.0.2:9000", "10.0.0.3:9000"),
                clientTracker.transactionWriteHolders(clientId, List.of("db|a", "db|b", "db|c")));
        assertEquals(Set.of("local"), clientTracker.transactionWriteHolders(clientId, List.of("db|a")));
        assertTrue(clientTracker.transactionWriteHolders(clientId, List.of("db|c")).isEmpty());
    }

    @Test
    public void test_clear_transaction_state_forgets_write_holders() {
        final var clientTracker = new ClientTracker();
        final var clientId = clientTracker.registerForwardedClient("u");
        clientTracker.recordTransactionWrite(clientId, "db|a", "local");

        clientTracker.clearTransactionState(clientId);

        assertTrue(clientTracker.transactionWriteHolders(clientId, List.of("db|a")).isEmpty());
    }

    @Test
    public void test_unknown_client_has_no_write_holders() {
        final var clientTracker = new ClientTracker();
        clientTracker.recordTransactionWrite(UUID.randomUUID(), "db|a", "local");
        clientTracker.recordTransactionWrite(null, "db|a", "local");

        assertTrue(clientTracker.transactionWriteHolders(null, List.of("db|a")).isEmpty());
        assertTrue(clientTracker.transactionWriteHolders(UUID.randomUUID(), List.of("db|a")).isEmpty());
    }

    @Test
    public void test_signalling_an_unknown_client_returns_no_departure() {
        assertNull(new ClientTracker().signalDisconnect(UUID.randomUUID()));
    }

    @Test
    public void test_a_signalled_client_departs_only_once_it_is_removed() throws Exception {
        final var clientTracker = new ClientTracker();
        final var socket = TestUtils.clientSocket();
        final var clientId = clientTracker.addClient(socket);
        final var bystander = clientTracker.addClient(TestUtils.clientSocket());

        final var departure = clientTracker.signalDisconnect(clientId);

        assertNotNull(departure);
        Mockito.verify(socket, Mockito.timeout(5000)).close();
        clientTracker.removeById(bystander);
        assertEquals(1, departure.getCount(), "another client's removal must not release this departure");
        clientTracker.removeById(clientId);
        assertEquals(0, departure.getCount(),
                "the connection thread removes the client after its disconnect cleanup, which is the departure");
    }

    private static void withMaxConnections(int maxConnections, ThrowingRunnable body) throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "maxConnections", maxConnections);
        try {
            body.run();
        } finally {
            TestUtils.setPrivateField(config, "maxConnections", ConfigKey.MAX_CONNECTIONS.defaultValue());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void test_concurrent_admission_never_exceeds_max_connections() throws Exception {
        withMaxConnections(5, () -> {
            final var clientTracker = new ClientTracker();
            final var start = new CountDownLatch(1);
            final var admitted = new AtomicInteger();
            final var sockets = new ArrayList<Socket>();
            for (var i = 0; i < 64; i++) {
                sockets.add(TestUtils.clientSocket());
            }
            final var threads = new ArrayList<Thread>();
            for (final var socket : sockets) {
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (clientTracker.addClient(socket) != null) {
                        admitted.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (final var thread : threads) {
                thread.join(5000);
            }
            assertEquals(5, admitted.get());
        });
    }

    @Test
    public void test_a_removed_client_frees_its_slot() throws Exception {
        withMaxConnections(1, () -> {
            final var clientTracker = new ClientTracker();
            final var first = clientTracker.addClient(TestUtils.clientSocket());

            assertNull(clientTracker.addClient(TestUtils.clientSocket()));
            clientTracker.removeById(first);
            clientTracker.removeById(first);
            assertNotNull(clientTracker.addClient(TestUtils.clientSocket()));
            assertNull(clientTracker.addClient(TestUtils.clientSocket()),
                    "removing the same client twice must free its slot only once");
        });
    }

    @Test
    public void test_forwarded_clients_do_not_consume_slots() throws Exception {
        withMaxConnections(1, () -> {
            final var clientTracker = new ClientTracker();
            final var forwarded = clientTracker.registerForwardedClient("alice");
            clientTracker.registerTxSession("session-1", "alice", "node-1");
            try {
                final var socketClient = clientTracker.addClient(TestUtils.clientSocket());

                assertNotNull(socketClient);
                clientTracker.removeById(forwarded);
                assertNull(clientTracker.addClient(TestUtils.clientSocket()),
                        "removing a forwarded client must not free a socket client's slot");
            } finally {
                clientTracker.removeTxSession("session-1");
            }
        });
    }

    @Test
    public void test_zero_means_unlimited_and_still_counts() throws Exception {
        final var clientTracker = new ClientTracker();
        final var admitted = new ArrayList<UUID>();
        withMaxConnections(0, () -> {
            for (var i = 0; i < 3; i++) {
                admitted.add(clientTracker.addClient(TestUtils.clientSocket()));
            }
            assertFalse(admitted.contains(null));
        });
        withMaxConnections(3, () -> assertNull(clientTracker.addClient(TestUtils.clientSocket())));
        admitted.forEach(clientTracker::removeById);
        withMaxConnections(1, () -> assertNotNull(clientTracker.addClient(TestUtils.clientSocket())));
    }

    @Test
    public void test_reaped_flag_is_consumed_once() {
        final var clientTracker = new ClientTracker();
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());

        assertNull(clientTracker.consumeTransactionReaped(clientId));
        clientTracker.markTransactionReaped(clientId, Client.TransactionReap.IDLE);
        assertEquals(Client.TransactionReap.IDLE, clientTracker.consumeTransactionReaped(clientId));
        assertNull(clientTracker.consumeTransactionReaped(clientId));
        clientTracker.markTransactionReaped(clientId, Client.TransactionReap.MAX_AGE);
        assertEquals(Client.TransactionReap.MAX_AGE, clientTracker.consumeTransactionReaped(clientId));
        assertDoesNotThrow(() -> clientTracker.markTransactionReaped(null, Client.TransactionReap.IDLE));
        assertDoesNotThrow(() -> clientTracker.markTransactionReaped(UUID.randomUUID(), Client.TransactionReap.IDLE));
        assertNull(clientTracker.consumeTransactionReaped(null));
        assertNull(clientTracker.consumeTransactionReaped(UUID.randomUUID()));
        clientTracker.removeById(clientId);
    }

    @Test
    public void deauthenticate_user_except_spares_the_named_client()
            throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        UUID kept = connect(clientTracker, "erin");
        UUID dropped = connect(clientTracker, "erin");
        UUID other = connect(clientTracker, "frank");

        clientTracker.deauthenticateUserExcept("erin", kept);

        assertEquals("erin", clientTracker.getAuthenticatedUsername(kept));
        assertNull(clientTracker.getAuthenticatedUsername(dropped));
        assertEquals("frank", clientTracker.getAuthenticatedUsername(other));
    }

    @Test
    public void forwarded_and_tx_session_clients_are_not_deauthenticated_by_except_variant() {
        ClientTracker clientTracker = new ClientTracker();
        UUID forwarded = clientTracker.registerForwardedClient("erin");
        var session = clientTracker.registerTxSession("session-except", "erin", "edge");
        try {
            clientTracker.deauthenticateUserExcept("erin", null);

            assertEquals("erin", clientTracker.getAuthenticatedUsername(forwarded));
            assertEquals("erin", clientTracker.getAuthenticatedUsername(session.clientId()));
        } finally {
            clientTracker.removeTxSession("session-except");
        }
    }

    @Test
    public void record_failed_authentication_counts_per_client_and_ignores_null()
            throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        UUID first = connect(clientTracker, null);
        UUID second = connect(clientTracker, null);

        assertEquals(1, clientTracker.recordFailedAuthentication(first));
        assertEquals(2, clientTracker.recordFailedAuthentication(first));
        assertEquals(1, clientTracker.recordFailedAuthentication(second));
        assertEquals(0, clientTracker.recordFailedAuthentication(null));
        assertEquals(0, clientTracker.recordFailedAuthentication(UUID.randomUUID()));
    }

    @Test
    public void address_of_returns_unknown_for_missing_client() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "maxConnections", 0);
        ClientTracker clientTracker = new ClientTracker();
        UUID clientId = connect(clientTracker, null);

        assertEquals("127.0.0.1", clientTracker.addressOf(clientId));
        assertEquals("unknown", clientTracker.addressOf(null));
        assertEquals("unknown", clientTracker.addressOf(UUID.randomUUID()));
    }
}
