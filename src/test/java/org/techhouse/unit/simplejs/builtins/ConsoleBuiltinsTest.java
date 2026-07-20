package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.ConsoleBuiltins;
import org.techhouse.simplejs.internal.Interpreter;

public class ConsoleBuiltinsTest {
    private final List<String> captured = new ArrayList<>();

    @AfterEach
    public void resetSink() {
        ConsoleBuiltins.setSink(System.out::println);
    }

    // console.log joins its arguments with spaces and writes to the sink
    @Test
    public void test_console_log() {
        ConsoleBuiltins.setSink(captured::add);
        Interpreter.run("console.log('a', 1, true)");
        assertEquals(List.of("a 1 true"), captured);
    }

    // error/warn/info all route to the sink
    @Test
    public void test_console_levels() {
        ConsoleBuiltins.setSink(captured::add);
        Interpreter.run("console.error('e'); console.warn('w'); console.info('i');");
        assertEquals(List.of("e", "w", "i"), captured);
    }
}
