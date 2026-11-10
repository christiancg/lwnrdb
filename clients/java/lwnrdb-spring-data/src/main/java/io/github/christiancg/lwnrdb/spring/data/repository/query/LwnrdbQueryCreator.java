package io.github.christiancg.lwnrdb.spring.data.repository.query;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Filters;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbQuerySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.repository.query.ParameterAccessor;
import org.springframework.data.repository.query.parser.AbstractQueryCreator;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.util.ObjectUtils;

public class LwnrdbQueryCreator extends AbstractQueryCreator<LwnrdbQuerySpec, Filter> {
    private static final Set<Part.Type> UNSUPPORTED = EnumSet.of(Part.Type.LIKE, Part.Type.NOT_LIKE,
            Part.Type.STARTING_WITH, Part.Type.ENDING_WITH, Part.Type.NOT_CONTAINING, Part.Type.REGEX, Part.Type.EXISTS,
            Part.Type.IS_EMPTY, Part.Type.IS_NOT_EMPTY, Part.Type.NEAR);

    private final LwnrdbOperations operations;
    private final Class<?> type;

    public LwnrdbQueryCreator(PartTree tree, ParameterAccessor parameters, LwnrdbOperations operations, Class<?> type) {
        super(tree, parameters);
        this.operations = operations;
        this.type = type;
    }

    public static void validate(PartTree tree, String methodName, LwnrdbOperations operations, Class<?> type) {
        for (final var part : tree.getParts()) {
            requireStoredProperty(part, methodName, operations, type);
            if (UNSUPPORTED.contains(part.getType())) {
                throw new InvalidDataAccessApiUsageException(
                        methodName + " uses " + part.getType() + ", which LWNRDB cannot query");
            }
            if (part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER
                    && Document.ID_FIELD.equals(operations.wirePath(type, part.getProperty().toDotPath()))) {
                throw new InvalidDataAccessApiUsageException(
                        methodName + " ignores case on the id, which LWNRDB" + " always compares exactly");
            }
        }
        if (tree.getSort().stream().count() > 1) {
            throw new InvalidDataAccessApiUsageException(
                    methodName + " orders by more than one property; LWNRDB" + " sorts by one");
        }
    }

    private static void requireStoredProperty(Part part, String methodName, LwnrdbOperations operations,
            Class<?> type) {
        try {
            operations.wirePath(type, part.getProperty().toDotPath());
        } catch (MappingException e) {
            throw new InvalidDataAccessApiUsageException(
                    methodName + " queries " + part.getProperty().toDotPath() + ", which is not stored", e);
        }
    }

    @Override
    protected Filter create(Part part, Iterator<Object> values) {
        final var path = operations.wirePath(type, part.getProperty().toDotPath());
        final var field = Filters.field(path);
        return switch (part.getType()) {
            case SIMPLE_PROPERTY -> field.eq(values.next());
            case NEGATING_SIMPLE_PROPERTY -> field.ne(values.next());
            case TRUE -> field.eq(true);
            case FALSE -> field.eq(false);
            case IS_NULL -> field.eq(null);
            case IS_NOT_NULL -> field.ne(null);
            case GREATER_THAN, AFTER -> field.gt(values.next());
            case GREATER_THAN_EQUAL -> field.gte(values.next());
            case LESS_THAN, BEFORE -> field.lt(values.next());
            case LESS_THAN_EQUAL -> field.lte(values.next());
            case BETWEEN -> Filters.and(field.gte(values.next()), field.lte(values.next()));
            case IN -> field.in(listOf(values.next()));
            case NOT_IN -> field.notIn(listOf(values.next()));
            case CONTAINING -> field.contains(values.next());
            case WITHIN -> Filters.within(path, geoPoints(values.next()));
            default -> throw new InvalidDataAccessApiUsageException("LWNRDB cannot query " + part.getType());
        };
    }

    @Override
    protected Filter and(Part part, Filter base, Iterator<Object> values) {
        return Filters.and(base, create(part, values));
    }

    @Override
    protected Filter or(Filter base, Filter criteria) {
        return Filters.or(base, criteria);
    }

    @Override
    protected LwnrdbQuerySpec complete(@Nullable Filter criteria, Sort sort) {
        return new LwnrdbQuerySpec(Optional.ofNullable(criteria), sort, 0, OptionalInt.empty());
    }

    private static List<@Nullable Object> listOf(@Nullable Object value) {
        if (value instanceof Iterable<?> iterable) {
            final var items = new ArrayList<@Nullable Object>();
            iterable.forEach(items::add);
            return items;
        }
        if (value != null && value.getClass().isArray()) {
            return Arrays.asList(ObjectUtils.toObjectArray(value));
        }
        return Collections.singletonList(value);
    }

    private static List<GeoPoint> geoPoints(Object value) {
        final var points = new ArrayList<GeoPoint>();
        for (final var item : listOf(value)) {
            if (!(item instanceof GeoPoint point)) {
                throw new InvalidDataAccessApiUsageException("Within takes GeoPoints, got " + item);
            }
            points.add(point);
        }
        return points;
    }
}
