package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.ModuleReferences;
import org.techhouse.simplejs.SimpleJs;

public class ModuleReferencesTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private ModuleReferences scan(String source) {
        return simpleJs.moduleReferences(simpleJs.compile(source, false));
    }

    @Test
    public void test_a_static_import_names_its_procedure() {
        final var references = scan("import { a } from 'procedures/lib'; return a;");

        assertEquals(Set.of("lib"), references.procedureNames());
        assertFalse(references.opaque());
    }

    @Test
    public void test_a_re_export_names_its_procedure() {
        final var references = scan("export * from 'procedures/lib'; export { b } from 'procedures/other';");

        assertEquals(Set.of("lib", "other"), references.procedureNames());
        assertFalse(references.opaque());
    }

    @Test
    public void test_a_literal_dynamic_import_names_its_procedure() {
        final var plain = scan("const ns = await import('procedures/lib'); return ns.a;");
        final var withOptions = scan("const ns = await import(\"procedures/opt\", {}); return ns.a;");
        final var inTemplate = scan("return `${await import('procedures/tpl')}`;");

        assertEquals(Set.of("lib"), plain.procedureNames());
        assertFalse(plain.opaque());
        assertEquals(Set.of("opt"), withOptions.procedureNames());
        assertFalse(withOptions.opaque());
        assertEquals(Set.of("tpl"), inTemplate.procedureNames());
        assertFalse(inTemplate.opaque());
    }

    @Test
    public void test_a_computed_dynamic_import_is_opaque() {
        assertTrue(scan("const n = 'x'; await import('procedures/' + n);").opaque());
        assertTrue(scan("const n = 'x'; await import(`procedures/${n}`);").opaque());
        assertTrue(scan("const n = 'procedures/x'; await import(n);").opaque());
        assertTrue(scan("const n = 'x'; return `${await import(n)}`;").opaque());
        assertTrue(ModuleReferences.scan("import(", List.of()).opaque());
        assertTrue(ModuleReferences.scan("import('procedures/x'", List.of()).opaque());
    }

    @Test
    public void test_importing_the_script_module_is_opaque() {
        assertTrue(scan("import script from 'script'; return 1;").opaque());
        assertTrue(scan("const s = await import('script'); return 1;").opaque());
    }

    @Test
    public void test_built_in_and_foreign_specifiers_are_not_procedures() {
        final var references = scan("import db from 'db'; import args from 'args'; import x from 'procedures/'; "
                + "import y from 'procedures/a/b'; import z from 'elsewhere/lib'; return 1;");

        assertTrue(references.procedureNames().isEmpty());
        assertFalse(references.opaque());
    }

    @Test
    public void test_the_word_import_in_a_string_or_a_property_is_not_an_import() {
        final var references = scan(
                "const s = \"import(x)\"; const obj = { f() { return 1; } }; obj.import = 1; obj?.import; return s;");

        assertTrue(references.procedureNames().isEmpty());
        assertFalse(references.opaque());
        assertFalse(ModuleReferences.scan("obj.import(1); obj?.import(2);", List.of()).opaque());
    }

    @Test
    public void test_a_procedure_name_needs_the_prefix_and_one_segment() {
        assertEquals("lib", ModuleReferences.procedureNameOf("procedures/lib"));
        assertNull(ModuleReferences.procedureNameOf(null));
        assertNull(ModuleReferences.procedureNameOf("db"));
        assertNull(ModuleReferences.procedureNameOf("procedures/"));
        assertNull(ModuleReferences.procedureNameOf("procedures/a/b"));
    }
}
