package org.techhouse.ops.req.agg.mid_operators;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter(AccessLevel.PROTECTED)
@RequiredArgsConstructor
public class BaseMidOperator {
    private final MidOperationType type;
}
