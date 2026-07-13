package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.WriteGuard;

public class WriteGuardTest {

    @Test
    public void test_allow() {
        final var guard = WriteGuard.allow();
        assertEquals(WriteGuard.Kind.ALLOW, guard.kind());
        assertNull(guard.ownerAddress());
    }

    @Test
    public void test_not_owner_carries_address() {
        final var guard = WriteGuard.notOwner("10.0.0.1:9990");
        assertEquals(WriteGuard.Kind.NOT_OWNER, guard.kind());
        assertEquals("10.0.0.1:9990", guard.ownerAddress());
    }

    @Test
    public void test_no_quorum() {
        final var guard = WriteGuard.noQuorum();
        assertEquals(WriteGuard.Kind.NO_QUORUM, guard.kind());
        assertNull(guard.ownerAddress());
    }
}
