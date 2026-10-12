package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.validations.NameValidations;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestUtils;

public class NameValidationsTest {
    private static final String[] NAMES = {"abc", "a-b_c", "lib", "p".repeat(64), "p".repeat(65), "ab", "", " ", "..",
            ".", ".lib", "a/b", "a\\b", "lib\u0000", "lib\n", "café", "a b", "LIB_2"};
    private static final List<String> INPUTS = List.of("", "a", "ab", "abc", "a-b_c", "ABC123", "a".repeat(64),
            "a".repeat(65), "../x", "..", ".", "a/b", "a\\b", "a b", "a|b", "a.b", "ñandú", "abc\n", "abc\0", "_-_",
            "123", "x".repeat(63) + "!", "Zz9");

    @Test
    public void test_is_valid_procedure_name_agrees_with_the_wire_rule() {
        for (final var name : NAMES) {
            final var wire = RequestValidator.validate(new SaveProcedureRequest("mydb", name, "return 1;")).isValid();
            assertEquals(wire, NameValidations.isValidProcedureName(name), Arrays.toString(name.toCharArray()));
        }
    }

    @Test
    public void test_null_is_not_a_valid_procedure_name() {
        assertFalse(NameValidations.isValidProcedureName(null));
    }

    private static String pattern(String name) throws Exception {
        return TestUtils.getPrivateStaticField(NameValidations.class, name, String.class);
    }

    @Test
    public void isNameAgreesWithTheNamePattern() throws Exception {
        final var regex = pattern("NAME_PATTERN");
        for (final var input : INPUTS) {
            assertEquals(input.matches(regex), NameValidations.isName(input), () -> "isName disagrees on " + input);
        }
        assertFalse(NameValidations.isName(null));
    }

    @Test
    public void isIdAgreesWithTheIdPattern() throws Exception {
        final var regex = pattern("ID_PATTERN");
        for (final var input : INPUTS) {
            assertEquals(input.matches(regex), NameValidations.isId(input), () -> "isId disagrees on " + input);
        }
        assertFalse(NameValidations.isId(null));
    }

    @Test
    public void isUsernameAgreesWithTheUsernamePattern() throws Exception {
        final var regex = pattern("USERNAME_PATTERN");
        for (final var input : INPUTS) {
            assertEquals(input.matches(regex), NameValidations.isUsername(input),
                    () -> "isUsername disagrees on " + input);
        }
        assertFalse(NameValidations.isUsername(null));
    }

    @Test
    public void reservedDatabaseNamesFoldCase() {
        assertTrue(NameValidations.isReservedDbName("admin"));
        assertTrue(NameValidations.isReservedDbName("ADMIN"));
        assertTrue(NameValidations.isReservedDbName("admin_pages"));
        assertTrue(NameValidations.isReservedDbName("Cluster"));
        assertFalse(NameValidations.isReservedDbName("administrator"));
    }
}
