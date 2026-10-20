package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;

public class UnsyncedTestHarnessTest {

    @Test
    public void test_unit_tests_run_with_unsynced_writes() {
        assertFalse(Configuration.getInstance().isSyncWrites(),
                "UnsyncedWritesExtension must turn syncWrites off for every test; a test that checks forcing turns"
                        + " it on itself with RecordingForcer");
    }
}
