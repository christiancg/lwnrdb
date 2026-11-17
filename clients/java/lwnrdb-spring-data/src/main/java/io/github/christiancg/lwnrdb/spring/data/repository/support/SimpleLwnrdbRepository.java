package io.github.christiancg.lwnrdb.spring.data.repository.support;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbQuerySpec;
import io.github.christiancg.lwnrdb.spring.data.repository.LwnrdbRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

public class SimpleLwnrdbRepository<T> implements LwnrdbRepository<T, String> {
    private final LwnrdbEntityInformation<T> information;
    private final LwnrdbOperations operations;

    public SimpleLwnrdbRepository(LwnrdbEntityInformation<T> information, LwnrdbOperations operations) {
        this.information = information;
        this.operations = operations;
    }

    @Override
    public <S extends T> S save(S entity) {
        return operations.save(entity);
    }

    @Override
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        return operations.saveAll(entities);
    }

    @Override
    public Optional<T> findById(String id) {
        return operations.findById(id, type());
    }

    @Override
    public boolean existsById(String id) {
        return operations.existsById(id, type());
    }

    @Override
    public List<T> findAll() {
        return operations.findAll(type());
    }

    @Override
    public List<T> findAllById(Iterable<String> ids) {
        return operations.findAllById(ids, type());
    }

    @Override
    public long count() {
        return operations.count(type());
    }

    @Override
    public void deleteById(String id) {
        operations.deleteById(id, type());
    }

    @Override
    public void delete(T entity) {
        operations.delete(entity);
    }

    @Override
    public void deleteAllById(Iterable<? extends String> ids) {
        operations.deleteAllById(StreamSupport.stream(ids.spliterator(), false).map(String.class::cast).toList(),
                type());
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        entities.forEach(operations::delete);
    }

    @Override
    public void deleteAll() {
        operations.deleteAll(type());
    }

    @Override
    public List<T> findAll(Sort sort) {
        return operations.findAll(type(), sort);
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        var spec = LwnrdbQuerySpec.all().sorted(pageable.getSort());
        if (pageable.isPaged()) {
            spec = spec.page(pageable.getOffset(), pageable.getPageSize());
        }
        final var content = operations.find(type(), spec);
        return PageableExecutionUtils.getPage(content, pageable, () -> operations.count(type()));
    }

    private Class<T> type() {
        return information.getJavaType();
    }
}
