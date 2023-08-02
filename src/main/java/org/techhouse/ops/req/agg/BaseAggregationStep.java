package org.techhouse.ops.req.agg;

import lombok.*;

@Getter
@Setter(AccessLevel.PROTECTED)
@RequiredArgsConstructor
public class BaseAggregationStep {
    private final AggregationStepType type;
}
