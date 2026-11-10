package io.github.christiancg.lwnrdb.spring.data.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.christiancg.lwnrdb.client.Document;
import java.util.Arrays;
import org.springframework.data.annotation.Id;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;

public class LwnrdbPersistentProperty extends AnnotationBasedPersistentProperty<LwnrdbPersistentProperty> {
    public static final String ID_ALIAS = "id";

    public LwnrdbPersistentProperty(Property property, PersistentEntity<?, LwnrdbPersistentProperty> owner,
            SimpleTypeHolder simpleTypeHolder) {
        super(property, owner, simpleTypeHolder);
    }

    @Override
    public boolean isIdProperty() {
        return super.isIdProperty() || ID_ALIAS.equals(getName()) && !declaresIdAnnotation(getOwner().getType());
    }

    public String wireName() {
        return isIdProperty() ? Document.ID_FIELD : javaWireName();
    }

    public String javaWireName() {
        final var renamed = findAnnotation(JsonProperty.class);
        return renamed == null || renamed.value().isEmpty() ? getName() : renamed.value();
    }

    @Override
    @SuppressWarnings("NullableProblems")
    protected Association<LwnrdbPersistentProperty> createAssociation() {
        return new Association<>(this, null);
    }

    static boolean declaresIdAnnotation(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (Arrays.stream(current.getDeclaredFields()).anyMatch(field -> field.isAnnotationPresent(Id.class))) {
                return true;
            }
        }
        return false;
    }
}
