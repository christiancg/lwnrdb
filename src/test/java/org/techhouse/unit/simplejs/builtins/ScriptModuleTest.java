package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.ScriptModule;
import org.techhouse.simplejs.builtins.TextImporter;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class ScriptModuleTest {
    private final List<String> imported = new ArrayList<>();

    private final TextImporter recorder = (moduleId, source) -> {
        imported.add(moduleId + "|" + source);
        return new JsString(moduleId);
    };

    private static ResourceLimits enabled() {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, true,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH);
    }

    private static JsValue call(JsObject module, JsValue... args) {
        final var fn = (JsNativeFunction) module.get("importText");
        return fn.invoke(JsUndefined.getInstance(), List.of(args));
    }

    // The module exposes exactly one member, importText
    @Test
    public void test_module_shape() {
        final var module = ScriptModule.create(recorder, enabled(), null);
        assertEquals(List.of("importText"), List.copyOf(module.keys()));
        assertInstanceOf(JsNativeFunction.class, module.get("importText"));
    }

    // With no explicit id the module id is derived from the source text
    @Test
    public void test_default_module_id_is_content_addressed() {
        final var module = ScriptModule.create(recorder, enabled(), null);
        call(module, new JsString("export default 1;"));
        call(module, new JsString("export default 1;"));
        call(module, new JsString("export default 2;"));
        assertEquals(3, imported.size());
        assertEquals(imported.get(0), imported.get(1));
        assertNotEquals(imported.get(0), imported.get(2));
        assertTrue(imported.getFirst().startsWith("text:"));
    }

    // An explicit second argument overrides the derived id
    @Test
    public void test_explicit_module_id() {
        final var module = ScriptModule.create(recorder, enabled(), null);
        final var result = call(module, new JsString("export default 1;"), new JsString("mine"));
        assertEquals("mine|export default 1;", imported.getFirst());
        assertEquals("mine", ((JsString) result).getValue());
    }

    // Both arguments are coerced rather than arity-checked
    @Test
    public void test_arguments_are_coerced() {
        final var module = ScriptModule.create(recorder, enabled(), null);
        call(module);
        call(module, new JsNumber(1), new JsNumber(2));
        assertTrue(imported.getFirst().endsWith("|undefined"));
        assertEquals("2|1", imported.get(1));
    }

    // The capability is refused when the host has not enabled it
    @Test
    public void test_disabled_by_default() {
        final var intrinsics = new Intrinsics(null, null, null, null);
        final var module = ScriptModule.create(recorder, ResourceLimits.unlimited(), intrinsics);
        final var thrown = assertThrows(JsThrowException.class, () -> call(module, new JsString("export default 1;")));
        assertEquals("Script text import is not available",
                ((JsString) ((JsObject) thrown.getValue()).get("message")).getValue());
        assertTrue(imported.isEmpty());
    }

    // A null ResourceLimits is treated as "not enabled" rather than dereferenced
    @Test
    public void test_null_limits_is_disabled() {
        final var intrinsics = new Intrinsics(null, null, null, null);
        final var module = ScriptModule.create(recorder, null, intrinsics);
        assertThrows(JsThrowException.class, () -> call(module, new JsString("export default 1;")));
        assertTrue(imported.isEmpty());
    }
}
