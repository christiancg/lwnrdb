package org.techhouse.unit.data.auth;

import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.PasswordHasher;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordHasherTest {
    @Test
    public void test_hash_produces_verifiable_string() {
        final var password = "testPassword123";
        final var hash = PasswordHasher.hash(password);
        assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    public void test_verify_returns_false_for_wrong_password() {
        final var password = "testPassword123";
        final var hash = PasswordHasher.hash(password);
        assertFalse(PasswordHasher.verify("wrongPassword", hash));
    }

    @Test
    public void test_verify_returns_false_for_malformed_stored() {
        assertFalse(PasswordHasher.verify("password", "not_valid_format"));
    }

    @Test
    public void test_hash_is_non_deterministic() {
        final var password = "testPassword123";
        final var hash1 = PasswordHasher.hash(password);
        final var hash2 = PasswordHasher.hash(password);
        assertNotEquals(hash1, hash2);
        assertTrue(PasswordHasher.verify(password, hash1));
        assertTrue(PasswordHasher.verify(password, hash2));
    }

    @Test
    public void test_verify_with_null_stored() {
        assertFalse(PasswordHasher.verify("password", null));
    }

    @Test
    public void test_hash_for_unicode_password() {
        final var password = "testPassword™®©";
        final var hash = PasswordHasher.hash(password);
        assertTrue(PasswordHasher.verify(password, hash));
    }

    @Test
    public void test_verify_constant_time_does_not_throw() {
        final var password = "testPassword123";
        final var hash = PasswordHasher.hash(password);
        assertDoesNotThrow(() -> {
            PasswordHasher.verify("wrongPassword", hash);
            PasswordHasher.verify("x", hash);
        });
    }
}
