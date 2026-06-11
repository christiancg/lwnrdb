package org.techhouse.unit.ops.auth;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.auth.AuthorizationResult;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationResultTest {
    @Test
    public void test_allow_result_is_allowed() {
        final var result = AuthorizationResult.allow();
        assertTrue(result.isAllowed());
        assertNull(result.getReason());
    }

    @Test
    public void test_deny_result_is_not_allowed() {
        final var result = AuthorizationResult.deny("no permission");
        assertFalse(result.isAllowed());
        assertEquals("no permission", result.getReason());
    }

    @Test
    public void test_deny_with_empty_reason() {
        final var result = AuthorizationResult.deny("");
        assertFalse(result.isAllowed());
        assertEquals("", result.getReason());
    }
}
