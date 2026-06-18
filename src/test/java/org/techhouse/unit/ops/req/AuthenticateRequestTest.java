package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.AuthenticateRequest;

public class AuthenticateRequestTest {
    @Test
    public void test_authenticate_request_type() {
        final var req = new AuthenticateRequest();
        assertEquals(OperationType.AUTHENTICATE, req.getType());
    }

    @Test
    public void test_username_setter_getter() {
        final var req = new AuthenticateRequest();
        req.setUsername("test_user");
        assertEquals("test_user", req.getUsername());
    }

    @Test
    public void test_password_setter_getter() {
        final var req = new AuthenticateRequest();
        req.setPassword("test_pass");
        assertEquals("test_pass", req.getPassword());
    }

    @Test
    public void test_database_name_null() {
        final var req = new AuthenticateRequest();
        assertNull(req.getDatabaseName());
    }

    @Test
    public void test_collection_name_null() {
        final var req = new AuthenticateRequest();
        assertNull(req.getCollectionName());
    }
}
