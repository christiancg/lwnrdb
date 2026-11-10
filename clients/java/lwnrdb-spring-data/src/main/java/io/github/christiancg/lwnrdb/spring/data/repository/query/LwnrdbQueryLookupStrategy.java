package io.github.christiancg.lwnrdb.spring.data.repository.query;

import io.github.christiancg.lwnrdb.spring.data.core.LwnrdbOperations;
import java.lang.reflect.Method;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.DefaultParameters;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.RepositoryQuery;

public class LwnrdbQueryLookupStrategy implements QueryLookupStrategy {
    private final LwnrdbOperations operations;

    public LwnrdbQueryLookupStrategy(LwnrdbOperations operations) {
        this.operations = operations;
    }

    @Override
    public RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata, ProjectionFactory factory,
            NamedQueries namedQueries) {
        return new PartTreeLwnrdbQuery(new QueryMethod(method, metadata, factory, DefaultParameters::new), operations);
    }
}
