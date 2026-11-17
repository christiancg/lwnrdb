package io.github.christiancg.lwnrdb.spring.data.repository.support;

import io.github.christiancg.lwnrdb.spring.data.mapping.LwnrdbPersistentEntity;
import org.springframework.data.repository.core.support.PersistentEntityInformation;

public class LwnrdbEntityInformation<T> extends PersistentEntityInformation<T, String> {
    private final LwnrdbPersistentEntity<T> entity;

    public LwnrdbEntityInformation(LwnrdbPersistentEntity<T> entity) {
        super(entity);
        this.entity = entity;
    }

    public String collection() {
        return entity.collection();
    }

    public String database() {
        return entity.database();
    }
}
