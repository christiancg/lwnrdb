package io.github.christiancg.lwnrdb.client.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ArrayOperation(ArrayOperator operator, List<Object> operands) implements MidOperation {
    public ArrayOperation {
        Objects.requireNonNull(operator, "operator");
        operands = Collections.unmodifiableList(new ArrayList<>(operands));
        if (operands.isEmpty()) {
            throw new IllegalArgumentException(operator + " needs at least one operand");
        }
    }
}
