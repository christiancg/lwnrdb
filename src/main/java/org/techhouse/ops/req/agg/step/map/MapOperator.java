package org.techhouse.ops.req.agg.step.map;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.techhouse.ops.req.agg.BaseOperator;

@Getter
@Setter(AccessLevel.PROTECTED)
@RequiredArgsConstructor
public class MapOperator {
    private final MapOperationType type;
    private final String fieldName;
    private final BaseOperator condition;
}
