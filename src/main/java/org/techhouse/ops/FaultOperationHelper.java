package org.techhouse.ops;

import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.ArmFaultRequest;
import org.techhouse.ops.req.AwaitFaultRequest;
import org.techhouse.ops.req.ReleaseFaultRequest;
import org.techhouse.ops.resp.OperationResponse;

public final class FaultOperationHelper {
    private static final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private FaultOperationHelper() {
    }

    public static OperationResponse arm(ArmFaultRequest request) {
        if (!faultInjector.isEnabled()) {
            return disabled(OperationType.ARM_FAULT);
        }
        final var point = FaultInjector.parse(FaultPoint.class, request.getPoint());
        faultInjector.arm(point, FaultInjector.parse(FaultAction.class, request.getAction()), request.getMatch());
        return OperationResponse.ok(OperationType.ARM_FAULT, "Fault armed at " + point);
    }

    public static OperationResponse await(AwaitFaultRequest request) {
        if (!faultInjector.isEnabled()) {
            return disabled(OperationType.AWAIT_FAULT);
        }
        final var point = FaultInjector.parse(FaultPoint.class, request.getPoint());
        return faultInjector.awaitReached(point, request.getTimeoutMs())
                ? OperationResponse.ok(OperationType.AWAIT_FAULT, "Fault reached at " + point)
                : new OperationResponse(OperationType.AWAIT_FAULT, ErrorCode.FAULT_NOT_REACHED);
    }

    public static OperationResponse release(ReleaseFaultRequest request) {
        if (!faultInjector.isEnabled()) {
            return disabled(OperationType.RELEASE_FAULT);
        }
        if (request.getPoint() == null) {
            faultInjector.releaseAll();
            return OperationResponse.ok(OperationType.RELEASE_FAULT, "Every fault released");
        }
        final var point = FaultInjector.parse(FaultPoint.class, request.getPoint());
        faultInjector.release(point);
        return OperationResponse.ok(OperationType.RELEASE_FAULT, "Fault released at " + point);
    }

    private static OperationResponse disabled(OperationType type) {
        return new OperationResponse(type, ErrorCode.FAULT_INJECTION_DISABLED);
    }
}
