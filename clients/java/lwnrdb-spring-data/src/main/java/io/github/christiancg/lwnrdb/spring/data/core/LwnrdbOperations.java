package io.github.christiancg.lwnrdb.spring.data.core;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Query;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbMappingContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;

public interface LwnrdbOperations {
    <T> T save(T entity);

    <T> List<T> saveAll(Iterable<T> entities);

    <T> Optional<T> findById(String id, Class<T> type);

    <T> boolean existsById(String id, Class<T> type);

    <T> List<T> findAll(Class<T> type);

    <T> List<T> findAll(Class<T> type, Sort sort);

    <T> List<T> findAllById(Iterable<String> ids, Class<T> type);

    <T> List<T> find(Class<T> type, LwnrdbQuerySpec spec);

    <T> long count(Class<T> type);

    <T> long count(Class<T> type, @Nullable Filter filter);

    <T> boolean exists(Class<T> type, @Nullable Filter filter);

    <T> void deleteById(String id, Class<T> type);

    <T> void delete(T entity);

    <T> void deleteAllById(Iterable<String> ids, Class<T> type);

    <T> long deleteAll(Class<T> type);

    <T> List<T> delete(Class<T> type, LwnrdbQuerySpec spec);

    <T> long deleteWhere(Class<T> type, @Nullable Filter filter);

    <T> long deleteMatching(Class<T> type, LwnrdbQuerySpec spec);

    <T> Query<T> query(Class<T> type);

    <T> Collection<T> collection(Class<T> type);

    <R extends @Nullable Object> R execute(Function<LwnrdbClient, R> action);

    String wirePath(Class<?> type, String propertyPath);

    LwnrdbMappingContext getMappingContext();

    LwnrdbClient getClient();
}
