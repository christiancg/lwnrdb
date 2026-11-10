package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.internal.CustomValues;
import io.github.christiancg.lwnrdb.client.internal.Documents;
import io.github.christiancg.lwnrdb.client.internal.Json;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class Document {
    public static final String ID_FIELD = "_id";
    private static final char PATH_SEPARATOR = '.';

    static {
        Documents.install(new Documents.Access() {
            @Override
            public ObjectNode node(Document document) {
                return document.node;
            }

            @Override
            public Document wrap(ObjectNode node) {
                return new Document(node);
            }
        });
    }

    private final ObjectNode node;

    private Document(ObjectNode node) {
        this.node = node;
    }

    public static Document empty() {
        return new Document(JsonNodeFactory.instance.objectNode());
    }

    public static Document of(Object... fieldsAndValues) {
        if (fieldsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Document.of takes field/value pairs");
        }
        final var node = JsonNodeFactory.instance.objectNode();
        for (var i = 0; i < fieldsAndValues.length; i += 2) {
            if (!(fieldsAndValues[i] instanceof String field)) {
                throw new IllegalArgumentException("argument " + i + " must be a field name");
            }
            node.set(field, Json.defaults().toNode(fieldsAndValues[i + 1]));
        }
        return new Document(node);
    }

    public static Document from(Map<String, ?> fields) {
        final var node = Json.defaults().toNode(fields);
        return new Document((ObjectNode) node);
    }

    public static Document parse(String json) {
        return new Document(Json.defaults().parseObject(json));
    }

    public Document with(String field, Object value) {
        final var copy = node.deepCopy();
        copy.set(field, Json.defaults().toNode(value));
        return new Document(copy);
    }

    public Document without(String field) {
        final var copy = node.deepCopy();
        copy.remove(field);
        return new Document(copy);
    }

    public Optional<String> id() {
        return getString(ID_FIELD);
    }

    public boolean has(String path) {
        return find(path).isPresent();
    }

    public Set<String> fieldNames() {
        return new LinkedHashSet<>(node.propertyNames());
    }

    public Optional<Object> get(String path) {
        return find(path).map(Json.defaults()::toPlain);
    }

    public Optional<String> getString(String path) {
        return typed(path, "a string", JsonNode::isString, JsonNode::stringValue);
    }

    public OptionalDouble getDouble(String path) {
        final var value = typed(path, "a number", JsonNode::isNumber, JsonNode::doubleValue);
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }

    public OptionalLong getLong(String path) {
        final var value = typed(path, "a whole number", JsonNode::canConvertToExactIntegral, JsonNode::longValue);
        return value.isPresent() ? OptionalLong.of(value.get()) : OptionalLong.empty();
    }

    public Optional<Boolean> getBoolean(String path) {
        return typed(path, "a boolean", JsonNode::isBoolean, JsonNode::booleanValue);
    }

    public Optional<LocalDateTime> getDateTime(String path) {
        return custom(path, CustomValues::parseDateTime);
    }

    public Optional<LocalTime> getTime(String path) {
        return custom(path, CustomValues::parseTime);
    }

    public Optional<Instant> getInstant(String path, ZoneId zone) {
        return getDateTime(path).map(dateTime -> dateTime.atZone(zone).toInstant());
    }

    public Optional<GeoPoint> getGeo(String path) {
        return custom(path, CustomValues::parseGeo);
    }

    public Optional<Vector> getVector(String path) {
        return custom(path, CustomValues::parseVector);
    }

    public Optional<Document> getDocument(String path) {
        return typed(path, "an object", JsonNode::isObject, value -> new Document((ObjectNode) value));
    }

    public Optional<List<Object>> getList(String path) {
        return typed(path, "an array", JsonNode::isArray, value -> {
            final var items = new ArrayList<>();
            value.values().forEach(item -> items.add(Json.defaults().toPlain(item)));
            return items;
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        return (Map<String, Object>) Json.defaults().toPlain(node);
    }

    public String toJson() {
        return Json.defaults().text(node);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Document document && node.equals(document.node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public String toString() {
        return toJson();
    }

    private Optional<JsonNode> find(String path) {
        JsonNode current = node;
        var start = 0;
        while (current != null) {
            final var end = path.indexOf(PATH_SEPARATOR, start);
            final var segment = end < 0 ? path.substring(start) : path.substring(start, end);
            current = current.isObject() ? current.get(segment) : null;
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return current == null || current.isNull() ? Optional.empty() : Optional.of(current);
    }

    private <R> Optional<R> typed(String path, String expected, Function<JsonNode, Boolean> accepts,
            Function<JsonNode, R> read) {
        final var value = find(path);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!accepts.apply(value.get())) {
            throw new MappingException("field '" + path + "' is not " + expected + ": " + value.get(), null);
        }
        return Optional.of(read.apply(value.get()));
    }

    private <R> Optional<R> custom(String path, Function<String, R> parse) {
        final var text = getString(path);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse.apply(text.get()));
        } catch (RuntimeException e) {
            throw new MappingException("field '" + path + "' holds no valid value: " + text.get(), e);
        }
    }
}
