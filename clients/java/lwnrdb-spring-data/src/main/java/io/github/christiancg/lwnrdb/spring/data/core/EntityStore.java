package io.github.christiancg.lwnrdb.spring.data.core;

import io.github.christiancg.lwnrdb.client.Collection;
import io.github.christiancg.lwnrdb.client.TransactionCollection;
import io.github.christiancg.lwnrdb.client.query.Query;
import java.util.List;
import java.util.Optional;

interface EntityStore<T> {
    void save(T value);

    void saveAll(List<T> values);

    Optional<T> findById(String id);

    void delete(String id);

    Query<T> query();

    static <T> EntityStore<T> of(Collection<T> collection) {
        return new EntityStore<>() {
            @Override
            public void save(T value) {
                collection.save(value);
            }

            @Override
            public void saveAll(List<T> values) {
                collection.saveAll(values);
            }

            @Override
            public Optional<T> findById(String id) {
                return collection.findById(id);
            }

            @Override
            public void delete(String id) {
                collection.delete(id);
            }

            @Override
            public Query<T> query() {
                return collection.query();
            }
        };
    }

    static <T> EntityStore<T> of(TransactionCollection<T> collection) {
        return new EntityStore<>() {
            @Override
            public void save(T value) {
                collection.save(value);
            }

            @Override
            public void saveAll(List<T> values) {
                collection.saveAll(values);
            }

            @Override
            public Optional<T> findById(String id) {
                return collection.findById(id);
            }

            @Override
            public void delete(String id) {
                collection.delete(id);
            }

            @Override
            public Query<T> query() {
                return collection.query();
            }
        };
    }
}
