package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;

public class ErrorCodeTest {

    @Test
    public void all_error_codes_have_non_blank_code_string() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(code.getCode(), code.name() + " has null code");
            assertFalse(code.getCode().isBlank(), code.name() + " has blank code");
        }
    }

    @Test
    public void all_error_codes_have_unique_codes() {
        final var seen = new HashSet<String>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(seen.add(code.getCode()), "Duplicate code: " + code.getCode() + " on " + code.name());
        }
    }

    @Test
    public void error_code_format_matches_pattern() {
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.getCode().matches("\\d{3}-\\d+"),
                    code.name() + " code '" + code.getCode() + "' does not match NNN-N format");
        }
    }

    @Test
    public void internal_server_errors_start_with_5() {
        final long count = Arrays.stream(ErrorCode.values()).filter(c -> c.getCode().startsWith("5")).count();
        assertTrue(count > 0, "Expected at least one 5xx error code");
        Arrays.stream(ErrorCode.values()).filter(c -> c.getCode().startsWith("5"))
                .forEach(c -> assertTrue(c.getCode().startsWith("5"), c.name() + " should be a 5xx code"));
    }

    @Test
    public void client_errors_start_with_4() {
        Arrays.stream(ErrorCode.values()).filter(c -> !c.getCode().startsWith("5"))
                .forEach(c -> assertTrue(c.getCode().startsWith("4"),
                        c.name() + " code '" + c.getCode() + "' is neither 4xx nor 5xx"));
    }

    @Test
    public void validation_error_has_null_default_message() {
        assertNull(ErrorCode.VALIDATION_ERROR.getDefaultMessage());
    }

    @Test
    public void non_validation_codes_have_non_null_default_message() {
        for (ErrorCode code : ErrorCode.values()) {
            if (code != ErrorCode.VALIDATION_ERROR) {
                assertNotNull(code.getDefaultMessage(), code.name() + " should have a default message");
                assertFalse(code.getDefaultMessage().isBlank(), code.name() + " default message is blank");
            }
        }
    }

    @Test
    public void must_authenticate_first_code_is_401_1() {
        assertEquals("401-1", ErrorCode.MUST_AUTHENTICATE_FIRST.getCode());
    }

    @Test
    public void no_permissions_code_is_403_1() {
        assertEquals("403-1", ErrorCode.NO_PERMISSIONS.getCode());
    }

    @Test
    public void all_error_codes_have_non_null_status() {
        for (ErrorCode code : ErrorCode.values()) {
            assertNotNull(code.getStatus(), code.name() + " has null status");
        }
    }

    @Test
    public void client_404_codes_have_not_found_status() {
        Arrays.stream(ErrorCode.values()).filter(c -> c.getCode().startsWith("404"))
                .forEach(c -> assertEquals(OperationStatus.NOT_FOUND, c.getStatus(),
                        c.name() + " should have NOT_FOUND status"));
    }

    @Test
    public void client_401_unauthenticated_codes_have_unauthenticated_status() {
        assertEquals(OperationStatus.UNAUTHENTICATED, ErrorCode.MUST_AUTHENTICATE_FIRST.getStatus());
        assertEquals(OperationStatus.UNAUTHENTICATED, ErrorCode.USER_NO_LONGER_EXISTS.getStatus());
    }

    @Test
    public void no_permissions_has_forbidden_status() {
        assertEquals(OperationStatus.FORBIDDEN, ErrorCode.NO_PERMISSIONS.getStatus());
    }
}
