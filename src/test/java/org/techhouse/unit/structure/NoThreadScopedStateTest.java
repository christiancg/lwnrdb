package org.techhouse.unit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class NoThreadScopedStateTest {
    private static final Pattern THREAD_SCOPED_STATE = Pattern
            .compile("\\b(?:Inheritable)?ThreadLocal\\b|\\bScopedValue\\b");
    private static final Set<String> ALLOWED = Set.of();

    @Test
    public void test_thread_scoped_state_lives_only_in_the_allowed_files() {
        assertEquals(ALLOWED,
                SingleDefinitionTest.filesWhoseCodeMatches(JavaSources.under(JavaSources.MAIN), THREAD_SCOPED_STATE),
                "Per-request state travels as a parameter, a field of an object in scope, a (transient) request "
                        + "field or a return value — see code-conventions.md");
    }

    @Test
    public void test_a_synthetic_thread_local_is_caught_but_not_inside_a_string_or_comment() {
        final var field = JavaSources.parse("Field.java",
                "class Field { static final ThreadLocal<String> X = new ThreadLocal<>(); }");
        final var inheritable = JavaSources.parse("Inheritable.java",
                "class Inheritable { static final InheritableThreadLocal<String> X = null; }");
        final var scoped = JavaSources.parse("Scoped.java",
                "class Scoped { static final ScopedValue<String> X = ScopedValue.newInstance(); }");
        final var mentioned = JavaSources.parse("Mentioned.java",
                "class Mentioned { String s = \"ThreadLocal\"; }\n// a ScopedValue or an InheritableThreadLocal\n");
        assertEquals(Set.of("Field.java", "Inheritable.java", "Scoped.java"), SingleDefinitionTest
                .filesWhoseCodeMatches(List.of(field, inheritable, scoped, mentioned), THREAD_SCOPED_STATE));
    }
}
