package org.techhouse.test;

import static org.mockito.Mockito.mockStatic;

import org.mockito.MockedStatic;
import org.techhouse.ops.StagedTriggerRuns;
import org.techhouse.ops.TriggerHelper;

public final class TriggerStagingMocks {
    private TriggerStagingMocks() {
    }

    public static MockedStatic<TriggerHelper> mockTriggerHelper() {
        return mockStatic(TriggerHelper.class,
                invocation -> invocation.getMethod().getReturnType() == StagedTriggerRuns.class
                        ? StagedTriggerRuns.none()
                        : null);
    }
}
