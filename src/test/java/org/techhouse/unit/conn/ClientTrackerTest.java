package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Client;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class ClientTrackerTest {
    // Adding a client when under max connections returns a valid UUID
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

    // Adding a client when max connections are reached returns null
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

    // maxConnections of 0 means unlimited: clients are added beyond a small count
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

    // Successfully removes a client when a valid UUID is provided
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

    // Removing a client from an empty map
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

    // Successfully updates last command time for existing client
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

    // Handles null clientId gracefully without exceptions
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
        clientTracker.setAuthenticatedUser(clientId, "alice");

        assertEquals("alice", clientTracker.getAuthenticatedUsername(clientId));
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
        assertDoesNotThrow(() -> clientTracker.setAuthenticatedUser(null, "alice"));
    }

    @Test
    public void test_get_authenticated_username_null_client_id() {
        ClientTracker clientTracker = new ClientTracker();
        assertNull(clientTracker.getAuthenticatedUsername(null));
    }
}
