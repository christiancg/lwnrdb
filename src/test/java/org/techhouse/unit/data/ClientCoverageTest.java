package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.techhouse.data.Client;
import org.techhouse.test.TestUtils;

public class ClientCoverageTest {

    @Test
    public void test_equals_full_field_chain() throws Exception {
        final var a = new Client("addr");
        final var b = new Client("addr");
        // Equalize the connection timestamp so equals evaluates every field comparison.
        TestUtils.setPrivateField(b, "connectionTime",
                TestUtils.getPrivateField(a, "connectionTime", LocalDateTime.class));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setAuthenticatedUsername("u");
        assertNotEquals(a, b);
    }

    @Test
    public void test_equals_identity_null_and_other_type() {
        final var a = new Client("addr");
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not-a-client");
        assertNotEquals(a, new Client("other"));
        assertTrue(a.toString().contains("addr"));
    }
}
