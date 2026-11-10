package io.github.christiancg.lwnrdb.spring.data.repository.support;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbPersistentEntity;
import io.github.christiancg.lwnrdb.spring.data.repository.query.LwnrdbQueryLookupStrategy;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.ValueExpressionDelegate;

public class LwnrdbRepositoryFactory extends RepositoryFactorySupport {
    private final LwnrdbOperations operations;

    public LwnrdbRepositoryFactory(LwnrdbOperations operations) {
        this.operations = operations;
    }

    @Override
    public EntityInformation<?, ?> getEntityInformation(RepositoryMetadata metadata) {
        return information(metadata.getDomainType());
    }

    @Override
    protected Object getTargetRepository(RepositoryInformation metadata) {
        return getTargetRepositoryViaReflection(metadata, information(metadata.getDomainType()), operations);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return SimpleLwnrdbRepository.class;
    }

    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.@Nullable Key key,
            ValueExpressionDelegate delegate) {
        return Optional.of(new LwnrdbQueryLookupStrategy(operations));
    }

    @Override
    protected void validate(RepositoryMetadata metadata) {
        super.validate(metadata);
        if (metadata.getIdType() != String.class) {
            throw new MappingException(metadata.getRepositoryInterface().getName() + " declares a "
                    + metadata.getIdType().getSimpleName() + " id; LWNRDB ids are strings");
        }
    }

    private <T> LwnrdbEntityInformation<T> information(Class<T> type) {
        @SuppressWarnings("unchecked")
        final var entity = (LwnrdbPersistentEntity<T>) operations.getMappingContext().getRequiredPersistentEntity(type);
        entity.requiredIdProperty();
        entity.collection();
        entity.database();
        return new LwnrdbEntityInformation<>(entity);
    }
}
