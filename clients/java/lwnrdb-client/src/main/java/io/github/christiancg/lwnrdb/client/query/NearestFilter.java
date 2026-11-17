package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.values.Vector;
import java.util.Objects;

public final class NearestFilter implements Filter {
    private final String field;
    private final Vector query;
    private final int k;
    private final boolean exact;

    public NearestFilter(String field, Vector query, int k, boolean exact) {
        this.field = Objects.requireNonNull(field, "field");
        this.query = Objects.requireNonNull(query, "query");
        if (k <= 0) {
            throw new IllegalArgumentException("nearest needs k > 0, got " + k);
        }
        this.k = k;
        this.exact = exact;
    }

    public NearestFilter exact() {
        return new NearestFilter(field, query, k, true);
    }

    public String field() {
        return field;
    }

    public Vector query() {
        return query;
    }

    public int k() {
        return k;
    }

    public boolean isExact() {
        return exact;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NearestFilter that && k == that.k && exact == that.exact && field.equals(that.field)
                && query.equals(that.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, query, k, exact);
    }

    @Override
    public String toString() {
        return "NearestFilter[" + field + ", k=" + k + (exact ? ", exact" : "") + "]";
    }
}
