package io.github.christiancg.lwnrdb.client.mapping;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.internal.Documents;
import io.github.christiancg.lwnrdb.client.internal.Json;
import java.util.Arrays;
import tools.jackson.databind.node.ObjectNode;

public final class JacksonCodec<T> implements DocumentCodec<T> {
    private static final String ID_ALIAS = "id";

    private final Class<T> type;
    private final Json json;
    private final boolean aliasesId;

    public JacksonCodec(Class<T> type, Json json) {
        this.type = type;
        this.json = json;
        this.aliasesId = declaresProperty(type, ID_ALIAS) && !declaresProperty(type, Document.ID_FIELD);
    }

    @Override
    public Document encode(T value) {
        final var tree = json.toNode(value);
        if (!tree.isObject()) {
            throw new MappingException(type.getName() + " does not map to a JSON object", null);
        }
        final var node = (ObjectNode) tree;
        if (aliasesId && node.has(ID_ALIAS) && !node.has(Document.ID_FIELD)) {
            final var id = node.remove(ID_ALIAS);
            if (!id.isNull()) {
                node.set(Document.ID_FIELD, id);
            }
        }
        return Documents.wrap(node);
    }

    @Override
    public T decode(Document document) {
        var node = Documents.node(document);
        if (aliasesId && node.has(Document.ID_FIELD)) {
            node = node.deepCopy();
            node.set(ID_ALIAS, node.remove(Document.ID_FIELD));
        }
        try {
            return json.fromNode(node, type);
        } catch (MappingException | IllegalArgumentException e) {
            throw new MappingException("document " + document.id().orElse("without _id") + " does not map to "
                    + type.getName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean declaresProperty(Class<?> type, String name) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).anyMatch(component -> component.getName().equals(name));
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (Arrays.stream(current.getDeclaredFields()).anyMatch(field -> field.getName().equals(name))) {
                return true;
            }
        }
        return false;
    }
}
