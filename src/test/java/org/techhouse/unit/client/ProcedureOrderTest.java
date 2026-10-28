package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.client.Connection;
import org.techhouse.client.ProcedureOrder;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class ProcedureOrderTest {
    private static JsonObject procedure(String name, String source) {
        final var procedure = new JsonObject();
        procedure.add("name", new JsonString(name));
        procedure.add("source", new JsonString(source));
        return procedure;
    }

    private static String importing(String... names) {
        final var source = new StringBuilder(256);
        for (final var name : names) {
            source.append("import x from \"procedures/").append(name).append("\";\n");
        }
        return source.append("export default function () { return 1; }").toString();
    }

    private static List<String> namesOf(List<JsonObject> procedures) {
        final var names = new ArrayList<String>();
        for (final var procedure : procedures) {
            names.add(Connection.stringOf(procedure, "name"));
        }
        return names;
    }

    @Test
    public void test_dependency_comes_first() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("b")), procedure("b", importing())));
        assertEquals(List.of("b", "a"), namesOf(order.order()));
    }

    @Test
    public void test_chain_is_ordered() {
        final var order = new ProcedureOrder(
                List.of(procedure("c", importing("b")), procedure("b", importing("a")), procedure("a", importing())));
        assertEquals(List.of("a", "b", "c"), namesOf(order.order()));
    }

    @Test
    public void test_independent_keep_listed_order() {
        final var order = new ProcedureOrder(
                List.of(procedure("z", importing()), procedure("m", importing()), procedure("a", importing())));
        assertEquals(List.of("z", "m", "a"), namesOf(order.order()));
    }

    @Test
    public void test_cycle_keeps_listed_order() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("b")), procedure("b", importing("a")),
                procedure("self", importing("self"))));
        assertEquals(List.of("a", "b", "self"), namesOf(order.order()));
    }

    @Test
    public void test_a_dependant_of_a_cycle_still_follows_it() {
        final var order = new ProcedureOrder(List.of(procedure("c", importing("a")), procedure("a", importing("b")),
                procedure("b", importing("a"))));
        assertEquals(List.of("a", "c", "b"), namesOf(order.order()));
    }

    @Test
    public void test_unknown_import_ignored() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("ghost")), procedure("b", importing())));
        assertEquals(List.of("a", "b"), namesOf(order.order()));
    }

    @Test
    public void test_double_and_single_quoted_imports() {
        final var order = new ProcedureOrder(List.of(
                procedure("a", "import x from 'procedures/b';\nimport y from \"procedures/c\";\nexport default 1;"),
                procedure("b", importing()), procedure("c", importing())));
        assertEquals(List.of("b", "c", "a"), namesOf(order.order()));
    }

    @Test
    public void test_a_procedure_without_source_or_name() {
        final var nameless = new JsonObject();
        nameless.add("source", new JsonString(importing()));
        final var sourceless = new JsonObject();
        sourceless.add("name", new JsonString("bare"));
        final var order = new ProcedureOrder(List.of(nameless, sourceless));
        assertEquals(List.of("bare"), namesOf(order.order()));
    }

    @Test
    public void test_closure_includes_transitive_imports() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("b")), procedure("b", importing("c")),
                procedure("c", importing()), procedure("unrelated", importing())));
        assertEquals(Set.of("a", "b", "c"), order.closureOf(List.of("a")));
    }

    @Test
    public void test_closure_skips_missing_names() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("ghost")), procedure("b", importing())));
        assertEquals(Set.of("a"), order.closureOf(List.of("a", "missing")));
    }

    @Test
    public void test_closure_terminates_on_a_cycle() {
        final var order = new ProcedureOrder(List.of(procedure("a", importing("b")), procedure("b", importing("a"))));
        assertEquals(Set.of("a", "b"), order.closureOf(List.of("b")));
    }
}
