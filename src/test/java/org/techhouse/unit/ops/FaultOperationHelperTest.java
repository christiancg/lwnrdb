package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FaultOperationHelper;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.ArmFaultRequest;
import org.techhouse.ops.req.AwaitFaultRequest;
import org.techhouse.ops.req.ReleaseFaultRequest;
import org.techhouse.test.TestUtils;

public class FaultOperationHelperTest {
    private static final long AWAIT_MS = 10L;
    private final FaultInjector injector = IocContainer.get(FaultInjector.class);
    private final List<Integer> halts = new CopyOnWriteArrayList<>();
    private IntConsumer origHalt;

    @BeforeEach
    public void setUp() throws Exception {
        origHalt = TestUtils.getPrivateField(injector, "halt", IntConsumer.class);
        TestUtils.setPrivateField(injector, "halt", (IntConsumer) halts::add);
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        TestUtils.setPrivateField(injector, "halt", origHalt);
    }

    private static ArmFaultRequest arm(String point, String action, String match, Long delayMs) {
        final var request = new ArmFaultRequest();
        request.setPoint(point);
        request.setAction(action);
        request.setMatch(match);
        request.setDelayMs(delayMs);
        return request;
    }

    private static AwaitFaultRequest await(String point) {
        final var request = new AwaitFaultRequest();
        request.setPoint(point);
        request.setTimeoutMs(AWAIT_MS);
        return request;
    }

    private static ReleaseFaultRequest release(String point) {
        final var request = new ReleaseFaultRequest();
        request.setPoint(point);
        return request;
    }

    @Test
    public void test_every_fault_operation_answers_disabled_when_the_switch_is_off() throws Exception {
        TestUtils.setPrivateField(injector, "enabled", false);
        assertEquals("403-3", FaultOperationHelper.arm(arm("PEER_REPLY", "DROP", null, null)).getErrorCode());
        assertEquals("403-3", FaultOperationHelper.await(await("PEER_REPLY")).getErrorCode());
        assertEquals("403-3", FaultOperationHelper.release(release(null)).getErrorCode());
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
    }

    @Test
    public void test_arm_then_await_answers_ok_once_the_point_fired() {
        final var armed = FaultOperationHelper.arm(arm("delete_tombstone_reserved", "halt", "db|coll", null));
        assertEquals(OperationStatus.OK, armed.getStatus());
        injector.at(FaultPoint.DELETE_TOMBSTONE_RESERVED, "db|coll");
        assertEquals(List.of(137), halts);
        assertEquals(OperationStatus.OK, FaultOperationHelper.await(await("DELETE_TOMBSTONE_RESERVED")).getStatus());
    }

    @Test
    public void test_arm_passes_its_delay_through() {
        FaultOperationHelper.arm(arm("ANTI_ENTROPY_SNAPSHOTTED", "DELAY", null, 1L));
        injector.at(FaultPoint.ANTI_ENTROPY_SNAPSHOTTED, "db|coll");
        assertEquals(OperationStatus.OK, FaultOperationHelper.await(await("ANTI_ENTROPY_SNAPSHOTTED")).getStatus());
    }

    @Test
    public void test_await_answers_not_reached_when_the_point_never_fired() {
        FaultOperationHelper.arm(arm("PEER_REPLY", "DROP", "COMMIT_TX", null));
        assertEquals("408-3", FaultOperationHelper.await(await("PEER_REPLY")).getErrorCode());
    }

    @Test
    public void test_release_of_one_point_disarms_only_that_point() {
        FaultOperationHelper.arm(arm("PEER_REPLY", "DROP", null, null));
        FaultOperationHelper.arm(arm("WRITE_OWNERSHIP_RECHECKED", "HALT", null, null));
        assertEquals(OperationStatus.OK, FaultOperationHelper.release(release("PEER_REPLY")).getStatus());
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, "db|coll");
        assertEquals(List.of(137), halts);
    }

    @Test
    public void test_release_without_a_point_disarms_every_point() {
        FaultOperationHelper.arm(arm("PEER_REPLY", "DROP", null, null));
        FaultOperationHelper.arm(arm("WRITE_OWNERSHIP_RECHECKED", "HALT", null, null));
        assertEquals(OperationStatus.OK, FaultOperationHelper.release(release(null)).getStatus());
        assertFalse(injector.dropsAt(FaultPoint.PEER_REPLY, "COMMIT_TX"));
        injector.at(FaultPoint.WRITE_OWNERSHIP_RECHECKED, "db|coll");
        assertTrue(halts.isEmpty());
    }
}
