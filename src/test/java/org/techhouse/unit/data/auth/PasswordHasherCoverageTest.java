package org.techhouse.unit.data.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.PasswordHasher;

public class PasswordHasherCoverageTest {

    @Test
    public void test_hash_then_verify_succeeds() {
        final var stored = PasswordHasher.hash("s3cret");
        assertTrue(PasswordHasher.verify("s3cret", stored));
    }

    @Test
    public void test_verify_wrong_password_fails() {
        final var stored = PasswordHasher.hash("s3cret");
        assertFalse(PasswordHasher.verify("wrong", stored));
    }

    @Test
    public void test_verify_null_or_unprefixed_fails() {
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify("x", "not-a-hash"));
    }

    @Test
    public void test_verify_wrong_part_count_fails() {
        assertFalse(PasswordHasher.verify("x", "pbkdf2$120000$onlythree"));
    }

    @Test
    public void test_verify_length_mismatch_fails() {
        final var salt = Base64.getEncoder().encodeToString(new byte[16]);
        final var shortHash = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertFalse(PasswordHasher.verify("x", "pbkdf2$120000$" + salt + "$" + shortHash));
    }
}
