package io.github.christiancg.lwnrdb.spring.data.repository.support;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;

public class LwnrdbRepositoryFactoryBean<T extends Repository<S, String>, S>
        extends
            RepositoryFactoryBeanSupport<T, S, String> {
    private @Nullable LwnrdbOperations operations;

    public LwnrdbRepositoryFactoryBean(Class<? extends T> repositoryInterface) {
        super(repositoryInterface);
    }

    @SuppressWarnings("unused")
    public void setLwnrdbOperations(LwnrdbOperations operations) {
        this.operations = operations;
        setMappingContext(operations.getMappingContext());
    }

    @Override
    protected RepositoryFactorySupport createRepositoryFactory() {
        return new LwnrdbRepositoryFactory(
                Objects.requireNonNull(operations, "an LwnrdbOperations bean is required (templateRef)"));
    }

    @Override
    public void afterPropertiesSet() {
        Objects.requireNonNull(operations, "an LwnrdbOperations bean is required (templateRef)");
        super.afterPropertiesSet();
    }
}
