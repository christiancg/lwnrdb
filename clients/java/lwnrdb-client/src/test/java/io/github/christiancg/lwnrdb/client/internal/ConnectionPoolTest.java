package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.ServerAddress;
import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.PoolExhaustedException;
import io.github.christiancg.lwnrdb.testsupport.FakeServer;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

public class ConnectionPoolTest {
    @Test
    public void test_connections_are_created_lazily_and_reused() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything());
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).poolSize(2).build()))) {
            final WireConnection first;
            try (var lease = pool.borrow()) {
                first = lease.connection();
            }
            try (var lease = pool.borrow()) {
                assertSame(first, lease.connection());
            }
            assertEquals(1, server.acceptedConnections());
            assertEquals(1, pool.idleConnections());
        }
    }

    @Test
    public void test_exhaustion_waits_then_times_out() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything());
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).poolSize(1).build()));
                var held = pool.borrow()) {
            assertThrows(PoolExhaustedException.class, pool::borrow);
            held.close();
            pool.borrow().close();
        }
    }

    @Test
    public void test_a_discarded_or_dead_connection_is_not_reused() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything());
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()))) {
            final WireConnection first;
            try (var lease = pool.borrow()) {
                first = lease.connection();
                lease.discard();
            }
            assertFalse(first.isOpen());
            final WireConnection second;
            try (var lease = pool.borrow()) {
                second = lease.connection();
            }
            second.close();
            try (var lease = pool.borrow()) {
                assertTrue(lease.connection().isOpen());
            }
            assertEquals(3, server.acceptedConnections());
        }
    }

    @Test
    public void test_authentication_runs_once_per_connection() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything());
                var pool = new ConnectionPool(
                        Wire.factory(Wire.settings(server).credentials("alice", "secret").build()))) {
            pool.borrow().close();
            pool.borrow().close();
            final var authenticate = server.nextRequest();
            assertEquals("AUTHENTICATE", authenticate.get("type").asString());
            assertEquals("alice", authenticate.get("username").asString());
            assertEquals("secret", authenticate.get("password").asString());
        }
    }

    @Test
    public void test_a_refused_login_fails_the_borrow_and_frees_the_permit() throws Exception {
        try (var server = new FakeServer(
                (request, session) -> session.send(FakeServer.error(request, "ERROR", "401-3")));
                var pool = new ConnectionPool(
                        Wire.factory(Wire.settings(server).poolSize(1).credentials("alice", "wrong").build()))) {
            assertThrows(AuthenticationException.class, pool::borrow);
            assertThrows(AuthenticationException.class, pool::borrow);
        }
    }

    @Test
    public void test_hosts_are_tried_in_turn_and_all_failing_reports_the_last() throws Exception {
        final int deadPort;
        try (var socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        try (var server = new FakeServer(FakeServer.okToEverything())) {
            final var settings = Wire.settings(server).host(new ServerAddress("127.0.0.1", deadPort)).build();
            try (var pool = new ConnectionPool(Wire.factory(settings))) {
                for (var i = 0; i < 3; i++) {
                    try (var lease = pool.borrow()) {
                        lease.discard();
                    }
                }
            }
        }
        final var onlyDead = io.github.christiancg.lwnrdb.client.ClientSettings.builder().host("127.0.0.1", deadPort)
                .build();
        try (var pool = new ConnectionPool(Wire.factory(onlyDead))) {
            assertThrows(ConnectionLostException.class, pool::borrow);
        }
    }

    @Test
    public void test_close_says_goodbye_and_refuses_new_borrows() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything())) {
            final var pool = new ConnectionPool(Wire.factory(Wire.settings(server).build()));
            final var borrowed = pool.borrow();
            pool.borrow().close();
            final var closer = new Thread(pool::close);
            closer.start();
            Thread.sleep(100);
            borrowed.close();
            closer.join(5000);
            assertFalse(borrowed.connection().isOpen());
            assertEquals("CLOSE_CONNECTION", server.nextRequestOfType("CLOSE_CONNECTION").get("type").asString());
            assertThrows(ConnectionLostException.class, pool::borrow);
            pool.close();
        }
    }

    @Test
    public void test_an_interrupted_borrow_keeps_the_flag() throws Exception {
        try (var server = new FakeServer(FakeServer.okToEverything());
                var pool = new ConnectionPool(Wire.factory(Wire.settings(server).poolSize(1).build()));
                var held = pool.borrow()) {
            Thread.currentThread().interrupt();
            assertThrows(PoolExhaustedException.class, pool::borrow);
            assertTrue(Thread.interrupted());
            assertTrue(held.connection().isOpen());
        }
    }
}
