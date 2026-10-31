package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.ops.auth.AuthFailureThrottle;
import org.techhouse.ops.auth.VerifiedPasswords;
import org.techhouse.test.TestUtils;

public class VerifiedPasswordsTest {
    private static final String HASH = "pbkdf2$120000$c2FsdA==$aGFzaA==";
    private static final String OTHER_HASH = "pbkdf2$120000$c2FsdDI=$aGFzaDI=";
    private final Configuration config = Configuration.getInstance();
    private final VerifiedPasswords verified = new VerifiedPasswords();

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "authVerifierCacheEnabled", "true");
    }

    @Test
    public void matches_after_remember() {
        verified.remember("alice", HASH, "s3cret");

        assertTrue(verified.matches("alice", HASH, "s3cret"));
    }

    @Test
    public void nothing_matches_before_remember() {
        assertFalse(verified.matches("alice", HASH, "s3cret"));
    }

    @Test
    public void another_password_misses() {
        verified.remember("alice", HASH, "s3cret");

        assertFalse(verified.matches("alice", HASH, "s3cret2"));
        assertFalse(verified.matches("alice", HASH, ""));
    }

    @Test
    public void a_changed_stored_hash_misses() {
        verified.remember("alice", HASH, "s3cret");

        assertFalse(verified.matches("alice", OTHER_HASH, "s3cret"));
    }

    @Test
    public void another_user_misses() {
        verified.remember("alice", HASH, "s3cret");

        assertFalse(verified.matches("bob", HASH, "s3cret"));
    }

    @Test
    public void the_separator_keeps_hash_and_password_apart() {
        verified.remember("alice", HASH, "s3cret");

        assertFalse(verified.matches("alice", HASH + "s", "3cret"));
    }

    @Test
    public void a_new_remember_replaces_the_old_verifier() {
        verified.remember("alice", HASH, "s3cret");
        verified.remember("alice", OTHER_HASH, "renewed");

        assertFalse(verified.matches("alice", HASH, "s3cret"));
        assertTrue(verified.matches("alice", OTHER_HASH, "renewed"));
        assertEquals(1, verified.size());
    }

    @Test
    public void another_process_key_never_matches() {
        verified.remember("alice", HASH, "s3cret");

        assertFalse(new VerifiedPasswords().matches("alice", HASH, "s3cret"));
    }

    @Test
    public void bounded_as_an_access_ordered_lru() {
        final var bounded = new VerifiedPasswords(2);
        bounded.remember("alice", HASH, "a");
        bounded.remember("bob", HASH, "b");
        assertTrue(bounded.matches("alice", HASH, "a"));
        bounded.remember("carol", HASH, "c");

        assertEquals(2, bounded.size());
        assertTrue(bounded.matches("alice", HASH, "a"));
        assertFalse(bounded.matches("bob", HASH, "b"));
        assertTrue(bounded.matches("carol", HASH, "c"));
    }

    @Test
    public void default_bound_matches_the_throttle() {
        assertEquals(AuthFailureThrottle.MAX_KEYS, VerifiedPasswords.MAX_USERS);
    }

    @Test
    public void disabled_flag_neither_remembers_nor_matches() throws Exception {
        verified.remember("alice", HASH, "s3cret");
        TestUtils.setPrivateField(config, "authVerifierCacheEnabled", "false");

        assertFalse(verified.matches("alice", HASH, "s3cret"));
        verified.remember("bob", HASH, "b");
        assertEquals(1, verified.size());

        TestUtils.setPrivateField(config, "authVerifierCacheEnabled", "true");
        assertFalse(verified.matches("bob", HASH, "b"));
    }
}
