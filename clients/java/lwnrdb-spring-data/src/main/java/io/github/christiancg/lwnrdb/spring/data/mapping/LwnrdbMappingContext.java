package io.github.christiancg.lwnrdb.spring.data.mapping;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.mapping.context.AbstractMappingContext;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;

public class LwnrdbMappingContext extends AbstractMappingContext<LwnrdbPersistentEntity<?>, LwnrdbPersistentProperty> {
    private static final Set<Class<?>> STORED_AS_VALUES = Set.of(Document.class, GeoPoint.class, Vector.class,
            LocalDateTime.class, LocalTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class);

    private final @Nullable String defaultDatabase;

    public LwnrdbMappingContext(@Nullable String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
        setSimpleTypeHolder(new SimpleTypeHolder(STORED_AS_VALUES, true));
    }

    public @Nullable String defaultDatabase() {
        return defaultDatabase;
    }

    @Override
    protected <T> LwnrdbPersistentEntity<T> createPersistentEntity(TypeInformation<T> typeInformation) {
        return new LwnrdbPersistentEntity<>(typeInformation, defaultDatabase);
    }

    @Override
    protected LwnrdbPersistentProperty createPersistentProperty(Property property, LwnrdbPersistentEntity<?> owner,
            SimpleTypeHolder simpleTypeHolder) {
        return new LwnrdbPersistentProperty(property, owner, simpleTypeHolder);
    }
}
