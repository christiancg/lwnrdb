package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.config.Globals;
import org.techhouse.conn.ConnectionLog;
import org.techhouse.log.Logger;

public class ConnectionLogTest {

    private static List<String> warningsOf(Logger logger) {
        final var captured = new ArrayList<String>();
        Mockito.doAnswer(invocation -> captured.add(invocation.getArgument(0))).when(logger)
                .warning(Mockito.anyString());
        return captured;
    }

    @Test
    public void test_pre_auth_warnings_are_rate_limited() throws Exception {
        final var logger = Mockito.mock(Logger.class);
        final var captured = warningsOf(logger);

        for (var i = 0; i < Globals.LOG_RATE_LIMIT_PER_SECOND * 5; i++) {
            ConnectionLog.warn(logger, "Dropped an unauthenticated connection " + i);
        }
        assertTrue(captured.size() <= Globals.LOG_RATE_LIMIT_PER_SECOND, captured.toString());

        Thread.sleep(1100);
        ConnectionLog.warn(logger, "a later warning");
        assertTrue(captured.getLast().startsWith("a later warning"), captured.getLast());
        assertTrue(captured.getLast().contains("similar entries suppressed"), captured.getLast());
    }
}
