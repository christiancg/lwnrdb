package io.github.christiancg.lwnrdb.client.query;

import java.util.List;
import java.util.Objects;

public record ConjunctionFilter(Conjunction conjunction, List<Filter> operands) implements Filter {
    public ConjunctionFilter {
        Objects.requireNonNull(conjunction, "conjunction");
        operands = List.copyOf(operands);
        if (operands.isEmpty()) {
            throw new IllegalArgumentException(conjunction + " needs at least one operand");
        }
    }
}
