package io.github.christiancg.lwnrdb.spring.data.repository;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.ListPagingAndSortingRepository;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface LwnrdbRepository<T, I> extends ListCrudRepository<T, I>, ListPagingAndSortingRepository<T, I> {
}
