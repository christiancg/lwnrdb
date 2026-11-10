package io.github.christiancg.lwnrdb.spring.data.mapping;

import io.github.christiancg.lwnrdb.client.internal.Names;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Transient;
import org.springframework.data.core.TypeInformation;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.model.BasicPersistentEntity;

public class LwnrdbPersistentEntity<T> extends BasicPersistentEntity<T, LwnrdbPersistentProperty> {
    private final @Nullable String defaultDatabase;

    public LwnrdbPersistentEntity(TypeInformation<T> information, @Nullable String defaultDatabase) {
        super(information);
        this.defaultDatabase = defaultDatabase;
    }

    public String collection() {
        final var annotation = findAnnotation(LwnrdbEntity.class);
        final var name = annotation == null || annotation.collection().isEmpty()
                ? Introspector.decapitalize(getType().getSimpleName())
                : annotation.collection();
        return checked(() -> Names.collection(name));
    }

    public String database() {
        final var annotation = findAnnotation(LwnrdbEntity.class);
        final var name = annotation == null || annotation.database().isEmpty()
                ? defaultDatabase
                : annotation.database();
        if (name == null || name.isEmpty()) {
            throw new MappingException(getType().getName() + " names no database: set @LwnrdbEntity(database = ...)"
                    + " or the default database (lwnrdb.database)");
        }
        return checked(() -> Names.database(name));
    }

    public LwnrdbPersistentProperty requiredIdProperty() {
        final var id = getIdProperty();
        if (id == null) {
            throw new MappingException(
                    getType().getName() + " has no id: annotate a String property with @Id or" + " name it id");
        }
        return id;
    }

    public String idJavaWireName() {
        return requiredIdProperty().javaWireName();
    }

    public List<String> transientWireNames() {
        final var names = new ArrayList<String>();
        for (Class<?> current = getType(); current != null
                && current != Object.class; current = current.getSuperclass()) {
            for (final var field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Transient.class)) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }

    @Override
    public void verify() {
        super.verify();
        final var id = getIdProperty();
        if (id != null && id.getType() != String.class) {
            throw new MappingException(getType().getName() + "." + id.getName() + " is a "
                    + id.getType().getSimpleName() + "; LWNRDB ids are strings");
        }
        if (id != null && !LwnrdbPersistentProperty.ID_ALIAS.equals(id.getName()) && hasPlainIdField()) {
            throw new MappingException(getType().getName() + " has both @Id " + id.getName() + " and a field named"
                    + " id; rename the field named id");
        }
    }

    private boolean hasPlainIdField() {
        final var property = getPersistentProperty(LwnrdbPersistentProperty.ID_ALIAS);
        return property != null && !property.isIdProperty();
    }

    private String checked(java.util.function.Supplier<String> validation) {
        try {
            return validation.get();
        } catch (IllegalArgumentException e) {
            throw new MappingException(getType().getName() + ": " + e.getMessage(), e);
        }
    }
}
