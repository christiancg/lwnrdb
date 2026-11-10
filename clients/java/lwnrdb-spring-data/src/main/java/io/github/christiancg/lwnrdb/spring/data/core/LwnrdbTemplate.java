package io.github.christiancg.lwnrdb.spring.data.core;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Order;
import io.github.christiancg.lwnrdb.client.query.Query;
import io.github.christiancg.lwnrdb.spring.data.mapping.EntityCodec;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbMappingContext;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbPersistentEntity;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbPersistentProperty;
import io.github.christiancg.lwnrdb.spring.data.transaction.LwnrdbTransactionHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class LwnrdbTemplate implements LwnrdbOperations {
    private final LwnrdbClient client;
    private final LwnrdbMappingContext mappingContext;
    private final LwnrdbExceptionTranslator translator = new LwnrdbExceptionTranslator();
    private final Map<Class<?>, EntityCodec<?>> codecs = new ConcurrentHashMap<>();

    public LwnrdbTemplate(LwnrdbClient client, LwnrdbMappingContext mappingContext) {
        this.client = Objects.requireNonNull(client, "client");
        this.mappingContext = Objects.requireNonNull(mappingContext, "mappingContext");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T save(T entity) {
        Objects.requireNonNull(entity, "entity");
        final var type = (Class<T>) entity.getClass();
        return translate(() -> {
            final var stored = codec(type).withGeneratedId(entity);
            store(type).save(stored);
            return stored;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> saveAll(Iterable<T> entities) {
        final var values = StreamSupport.stream(entities.spliterator(), false).toList();
        if (values.isEmpty()) {
            return List.of();
        }
        final var type = (Class<T>) values.get(0).getClass();
        if (values.stream().anyMatch(value -> value.getClass() != type)) {
            throw new InvalidDataAccessApiUsageException("saveAll takes entities of one class");
        }
        return translate(() -> {
            final var codec = codec(type);
            final var stored = values.stream().map(codec::withGeneratedId).toList();
            store(type).saveAll(stored);
            return stored;
        });
    }

    @Override
    public <T> Optional<T> findById(String id, Class<T> type) {
        return translate(() -> store(type).findById(id));
    }

    @Override
    public <T> boolean existsById(String id, Class<T> type) {
        return findById(id, type).isPresent();
    }

    @Override
    public <T> List<T> findAll(Class<T> type) {
        return find(type, LwnrdbQuerySpec.all());
    }

    @Override
    public <T> List<T> findAll(Class<T> type, Sort sort) {
        return find(type, LwnrdbQuerySpec.all().sorted(sort));
    }

    @Override
    public <T> List<T> findAllById(Iterable<String> ids, Class<T> type) {
        final var values = StreamSupport.stream(ids.spliterator(), false).toList();
        if (values.isEmpty()) {
            return List.of();
        }
        return find(type, LwnrdbQuerySpec.of(field(Document.ID_FIELD).in(values)));
    }

    @Override
    public <T> List<T> find(Class<T> type, LwnrdbQuerySpec spec) {
        return translate(() -> build(type, spec).toList());
    }

    @Override
    public <T> long count(Class<T> type) {
        return count(type, null);
    }

    @Override
    public <T> long count(Class<T> type, @Nullable Filter filter) {
        return translate(() -> filtered(type, filter).count());
    }

    @Override
    public <T> boolean exists(Class<T> type, @Nullable Filter filter) {
        return translate(() -> filtered(type, filter).limit(1).first().isPresent());
    }

    @Override
    public <T> void deleteById(String id, Class<T> type) {
        translate(() -> {
            try {
                store(type).delete(id);
            } catch (NotFoundException e) {
                if (!e.hasCode(ErrorCode.ENTRY_NOT_FOUND)) {
                    throw e;
                }
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void delete(T entity) {
        final var type = (Class<T>) entity.getClass();
        final var id = codec(type).idOf(entity).orElseThrow(
                () -> new InvalidDataAccessApiUsageException("cannot delete a " + type.getName() + " that has no id"));
        deleteById(id, type);
    }

    @Override
    public <T> void deleteAllById(Iterable<String> ids, Class<T> type) {
        ids.forEach(id -> deleteById(id, type));
    }

    @Override
    public <T> long deleteAll(Class<T> type) {
        return delete(type, LwnrdbQuerySpec.all()).size();
    }

    @Override
    public <T> List<T> delete(Class<T> type, LwnrdbQuerySpec spec) {
        final var matching = find(type, spec);
        final var codec = codec(type);
        matching.forEach(entity -> codec.idOf(entity).ifPresent(id -> deleteById(id, type)));
        return matching;
    }

    @Override
    public <T> Query<T> query(Class<T> type) {
        return translate(() -> store(type).query());
    }

    @Override
    public <T> Collection<T> collection(Class<T> type) {
        final var entity = entity(type);
        return translate(() -> client.database(entity.database()).collection(entity.collection(), codec(type)));
    }

    @Override
    public <R extends @Nullable Object> R execute(Function<LwnrdbClient, R> action) {
        return translate(() -> action.apply(client));
    }

    @Override
    public String wirePath(Class<?> type, String propertyPath) {
        return mappingContext.getPersistentPropertyPath(propertyPath, type).stream()
                .map(LwnrdbPersistentProperty::wireName).collect(Collectors.joining("."));
    }

    @Override
    public LwnrdbMappingContext getMappingContext() {
        return mappingContext;
    }

    @Override
    public LwnrdbClient getClient() {
        return client;
    }

    private <T> Query<T> filtered(Class<T> type, @Nullable Filter filter) {
        final var query = store(type).query();
        return filter == null ? query : query.filter(filter);
    }

    private <T> Query<T> build(Class<T> type, LwnrdbQuerySpec spec) {
        var query = filtered(type, spec.filter().orElse(null));
        final var orders = new ArrayList<Sort.Order>();
        spec.sort().forEach(orders::add);
        if (orders.size() > 1) {
            throw new InvalidDataAccessApiUsageException("LWNRDB sorts by one property; got " + spec.sort());
        }
        for (final var order : orders) {
            query = query.sort(wirePath(type, order.getProperty()), order.isAscending() ? Order.ASC : Order.DESC);
        }
        if (spec.skip() > 0) {
            query = query.skip(Math.toIntExact(spec.skip()));
        }
        if (spec.limit().isPresent()) {
            query = query.limit(spec.limit().getAsInt());
        }
        return query;
    }

    @SuppressWarnings("resource")
    private <T> EntityStore<T> store(Class<T> type) {
        final var entity = entity(type);
        final var holder = (LwnrdbTransactionHolder) TransactionSynchronizationManager.getResource(client);
        if (holder != null) {
            return EntityStore.of(holder.transaction().collection(entity.database(), entity.collection(), codec(type)));
        }
        return EntityStore.of(client.database(entity.database()).collection(entity.collection(), codec(type)));
    }

    @SuppressWarnings("unchecked")
    private <T> EntityCodec<T> codec(Class<T> type) {
        return (EntityCodec<T>) codecs.computeIfAbsent(type,
                ignored -> new EntityCodec<>(client.codecFor(type), entity(type)));
    }

    @SuppressWarnings("unchecked")
    private <T> LwnrdbPersistentEntity<T> entity(Class<T> type) {
        return (LwnrdbPersistentEntity<T>) mappingContext.getRequiredPersistentEntity(type);
    }

    private void translate(Runnable action) {
        translate(() -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    private <R extends @Nullable Object> R translate(Supplier<R> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            final var translated = translator.translateExceptionIfPossible(e);
            throw translated != null ? translated : e;
        }
    }
}
