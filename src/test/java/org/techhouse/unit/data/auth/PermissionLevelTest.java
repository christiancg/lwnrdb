package org.techhouse.unit.data.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.PermissionLevel;

public class PermissionLevelTest {
    @Test
    public void test_read_write_covers_read() {
        assertTrue(PermissionLevel.READ_WRITE.covers(PermissionLevel.READ));
    }

    @Test
    public void test_read_does_not_cover_read_write() {
        assertFalse(PermissionLevel.READ.covers(PermissionLevel.READ_WRITE));
    }

    @Test
    public void test_read_covers_read() {
        assertTrue(PermissionLevel.READ.covers(PermissionLevel.READ));
    }

    @Test
    public void test_read_write_covers_read_write() {
        assertTrue(PermissionLevel.READ_WRITE.covers(PermissionLevel.READ_WRITE));
    }
}
