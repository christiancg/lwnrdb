package io.github.christiancg.lwnrdb.spring.data.core;

import io.github.christiancg.lwnrdb.client.query.Filter;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.data.domain.Sort;

public record LwnrdbQuerySpec(Optional<Filter> filter, Sort sort, long skip, OptionalInt limit) {
    public LwnrdbQuerySpec {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(limit, "limit");
        if (skip < 0) {
            throw new IllegalArgumentException("skip must not be negative: " + skip);
        }
    }

    public static LwnrdbQuerySpec all() {
        return new LwnrdbQuerySpec(Optional.empty(), Sort.unsorted(), 0, OptionalInt.empty());
    }

    public static LwnrdbQuerySpec of(Filter filter) {
        return new LwnrdbQuerySpec(Optional.of(filter), Sort.unsorted(), 0, OptionalInt.empty());
    }

    public LwnrdbQuerySpec sorted(Sort order) {
        return new LwnrdbQuerySpec(filter, sort.and(order), skip, limit);
    }

    public LwnrdbQuerySpec page(long offset, int size) {
        return new LwnrdbQuerySpec(filter, sort, offset, OptionalInt.of(size));
    }

    public LwnrdbQuerySpec limited(int maxResults) {
        final var narrower = limit.isPresent() ? Math.min(limit.getAsInt(), maxResults) : maxResults;
        return new LwnrdbQuerySpec(filter, sort, skip, OptionalInt.of(narrower));
    }
}
