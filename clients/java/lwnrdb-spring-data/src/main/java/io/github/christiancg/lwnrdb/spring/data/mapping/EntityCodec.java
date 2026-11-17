package io.github.christiancg.lwnrdb.spring.data.mapping;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EntityCodec<T> implements DocumentCodec<T> {
    private final DocumentCodec<T> jackson;
    private final String idJavaName;
    private final List<String> transientNames;

    public EntityCodec(DocumentCodec<T> jackson, LwnrdbPersistentEntity<T> entity) {
        this.jackson = jackson;
        this.idJavaName = entity.idJavaWireName();
        this.transientNames = entity.transientWireNames();
    }

    @Override
    public Document encode(T value) {
        var document = jackson.encode(value);
        if (renamesId() && document.has(idJavaName)) {
            final var id = document.get(idJavaName).orElseThrow();
            document = document.without(idJavaName).with(Document.ID_FIELD, id);
        } else if (renamesId()) {
            document = document.without(idJavaName);
        }
        for (final var name : transientNames) {
            document = document.without(name);
        }
        return document;
    }

    @Override
    public T decode(Document document) {
        var source = document;
        if (renamesId() && document.has(Document.ID_FIELD)) {
            source = document.without(Document.ID_FIELD).with(idJavaName, document.id().orElseThrow());
        }
        return jackson.decode(source);
    }

    public Optional<String> idOf(T value) {
        return encode(value).id();
    }

    public T withGeneratedId(T value) {
        final var document = encode(value);
        if (document.id().isPresent()) {
            return value;
        }
        return decode(document.with(Document.ID_FIELD, UUID.randomUUID().toString()));
    }

    private boolean renamesId() {
        return !LwnrdbPersistentProperty.ID_ALIAS.equals(idJavaName) && !Document.ID_FIELD.equals(idJavaName);
    }
}
