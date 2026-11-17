package io.github.christiancg.lwnrdb.spring.data.repository.query;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbQuerySpec;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.repository.query.ParametersParameterAccessor;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.ClassUtils;

public class PartTreeLwnrdbQuery implements RepositoryQuery {
    private static final int SINGLE_RESULT_PROBE = 2;

    private final QueryMethod method;
    private final LwnrdbOperations operations;
    private final Class<?> type;
    private final PartTree tree;

    public PartTreeLwnrdbQuery(QueryMethod method, LwnrdbOperations operations) {
        this.method = method;
        this.operations = operations;
        this.type = method.getEntityInformation().getJavaType();
        this.tree = new PartTree(method.getName(), type);
        LwnrdbQueryCreator.validate(tree, method.getName(), operations, type);
    }

    @Override
    public QueryMethod getQueryMethod() {
        return method;
    }

    @Override
    public @Nullable Object execute(@Nullable Object[] parameters) {
        final var accessor = new ParametersParameterAccessor(method.getParameters(), parameters);
        var spec = createSpec(accessor);
        final var maxResults = tree.getMaxResults();
        if (tree.isLimiting() && maxResults != null) {
            spec = spec.limited(maxResults);
        }
        if (accessor.getLimit().isLimited()) {
            spec = spec.limited(accessor.getLimit().max());
        }
        if (tree.isDelete()) {
            return returnsNumber() ? (Object) operations.deleteMatching(type, spec) : operations.delete(type, spec);
        }
        if (tree.isCountProjection()) {
            return operations.count(type, spec.filter().orElse(null));
        }
        if (tree.isExistsProjection()) {
            return operations.exists(type, spec.filter().orElse(null));
        }
        return select(spec, accessor.getPageable());
    }

    private LwnrdbQuerySpec createSpec(ParametersParameterAccessor accessor) {
        try {
            return new LwnrdbQueryCreator(tree, accessor, operations, type).createQuery();
        } catch (IllegalArgumentException e) {
            throw new InvalidDataAccessApiUsageException(method.getName() + ": " + e.getMessage(), e);
        }
    }

    private @Nullable Object select(LwnrdbQuerySpec spec, Pageable pageable) {
        if (method.isPageQuery()) {
            final var content = operations.find(type, paged(spec, pageable));
            return PageableExecutionUtils.getPage(content, pageable,
                    () -> operations.count(type, spec.filter().orElse(null)));
        }
        if (method.isSliceQuery()) {
            return slice(spec, pageable);
        }
        if (!method.isStreamQuery() && !method.isCollectionQuery()) {
            return single(spec);
        }
        final var results = operations.find(type, paged(spec, pageable));
        return method.isStreamQuery() ? results.stream() : results;
    }

    private Object slice(LwnrdbQuerySpec spec, Pageable pageable) {
        if (pageable.isUnpaged()) {
            final var all = operations.find(type, spec);
            return new SliceImpl<>(all, pageable, false);
        }
        final var size = pageable.getPageSize();
        final List<Object> probe = new ArrayList<>(operations.find(type, spec.page(pageable.getOffset(), size + 1)));
        final var hasNext = probe.size() > size;
        return new SliceImpl<>(hasNext ? probe.subList(0, size) : probe, pageable, hasNext);
    }

    private @Nullable Object single(LwnrdbQuerySpec spec) {
        final var results = operations.find(type, spec.limited(SINGLE_RESULT_PROBE));
        if (results.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(method.getName() + " expected one result", 1,
                    results.size());
        }
        return results.isEmpty() ? null : results.get(0);
    }

    private static LwnrdbQuerySpec paged(LwnrdbQuerySpec spec, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return spec;
        }
        return spec.page(pageable.getOffset(), pageable.getPageSize());
    }

    private boolean returnsNumber() {
        return Number.class.isAssignableFrom(ClassUtils.resolvePrimitiveIfNecessary(method.getReturnedObjectType()));
    }
}
