package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ModuleResolver;
import org.techhouse.simplejs.host.ResolvedModule;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.unit.simplejs.host.ModuleHostBindings;

public class HiddenModuleSourceTest {
    private static final String LIBRARY = "export function add(a, b) { return a + b; }"
            + " export const sum = function () { let t = 0; for (const v of arguments) t += v; return t; };"
            + " export class Box { constructor(v) { this.v = v; } get value() { return this.v; } }"
            + " export const make = () => (x) => x * 3;";

    private final SimpleJs engine = new SimpleJs();

    private String run(ResolvedModule module, String source) {
        final ModuleResolver resolver = (specifier, _) -> "lib".equals(specifier) ? module : null;
        return engine.run(source, ModuleHostBindings.of(resolver, ResourceLimits.unlimited())).getValue().asJsonString()
                .getValue();
    }

    private String hidden(String source) {
        return run(ResolvedModule.withHiddenSource("pkg:lib", LIBRARY, "lib"), source);
    }

    @Test
    public void test_hidden_functions_print_as_native_stubs() {
        assertEquals("function add() { [native code] }", hidden("import { add } from 'lib'; return String(add);"));
        assertEquals("function Box() { [native code] }", hidden("import { Box } from 'lib'; return `${Box}`;"));
        assertEquals("function () { [native code] }",
                hidden("import { make } from 'lib'; return Function.prototype.toString.call(make());"));
    }

    @Test
    public void test_hidden_functions_still_behave() {
        assertEquals("3|6|7|12", hidden("import { add, sum, Box, make } from 'lib';"
                + " return [add(1, 2), sum(1, 2, 3), new Box(7).value, make()(4)].join('|');"));
    }

    @Test
    public void test_a_revealed_module_keeps_its_source() {
        assertEquals("function add(a, b) { return a + b; }",
                run(new ResolvedModule("pkg:lib", LIBRARY), "import { add } from 'lib'; return String(add);"));
    }

    @Test
    public void test_parsing_without_source_text_drops_every_span() {
        final var program = Parser.parseWithoutSourceText(Lexer.lexWithPositions("function f(a) { return a; }"), false);
        assertNull(program.getBody().getFirst().getSourceText());
    }
}
