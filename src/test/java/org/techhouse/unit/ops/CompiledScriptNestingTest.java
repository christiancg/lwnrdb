package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CompiledScriptCache;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class CompiledScriptNestingTest {
    private static final int OVERFLOWING_NESTING = 200_000;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptMaxSourceBytes",
                ConfigKey.SCRIPT_MAX_SOURCE_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String deeplyNestedSource() {
        return "return " + "(".repeat(OVERFLOWING_NESTING) + "1" + ")".repeat(OVERFLOWING_NESTING) + ";";
    }

    @Test
    public void test_a_procedure_nested_past_the_stack_is_refused_as_invalid() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "scriptMaxSourceBytes", "1Mb");
        final var response = ProcedureOperationHelper
                .executeSave(new SaveProcedureRequest(TestGlobals.DB, "tooDeep", deeplyNestedSource()), "admin");
        assertEquals(ErrorCode.INVALID_PROCEDURE.getCode(), response.getErrorCode());
    }

    @Test
    public void test_a_source_nested_past_the_stack_compiles_to_a_syntax_failure() {
        final var compilation = IocContainer.get(CompiledScriptCache.class).get(deeplyNestedSource());
        assertNull(compilation.compiled());
        assertInstanceOf(SyntaxErrorException.class, compilation.failure());
        assertTrue(compilation.failure().getMessage().contains("nests more than"), compilation.failure().getMessage());
    }

    private static SyntaxErrorException failureOf(String source) {
        final var compilation = IocContainer.get(CompiledScriptCache.class).get(source);
        return compilation.failure() instanceof SyntaxErrorException syntax ? syntax : null;
    }

    @Test
    public void test_every_recursive_construct_stops_at_the_same_deterministic_depth() {
        for (final var source : List.of("return " + "(".repeat(300) + "1" + ")".repeat(300) + ";",
                "return " + "[".repeat(300) + "]".repeat(300) + ";", "return " + "!".repeat(300) + "1;",
                "return " + "2**".repeat(300) + "2;", "{".repeat(300) + "}".repeat(300),
                "function f(){".repeat(300) + "}".repeat(300), "const x = " + "() => ".repeat(300) + "1;",
                "return " + "`${".repeat(300) + "1" + "}`".repeat(300) + ";")) {
            final var failure = failureOf(source);
            assertTrue(failure != null && failure.getMessage().contains("levels deep"),
                    source.substring(0, 20) + " -> " + failure);
        }
    }

    @Test
    public void test_ordinary_nesting_well_inside_the_limit_compiles() {
        for (final var source : List.of("return " + "(".repeat(100) + "1" + ")".repeat(100) + ";",
                "return " + "!".repeat(150) + "1;", "{".repeat(150) + "}".repeat(150),
                "function f(){".repeat(100) + "}".repeat(100) + " return 1;")) {
            assertNull(failureOf(source), source.substring(0, 20));
        }
    }
}
