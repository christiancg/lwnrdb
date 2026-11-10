package io.github.christiancg.lwnrdb.client.mapping;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.internal.CustomValues;
import io.github.christiancg.lwnrdb.client.internal.Documents;
import io.github.christiancg.lwnrdb.client.values.GeoPoint;
import io.github.christiancg.lwnrdb.client.values.Vector;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

public final class LwnrdbModule extends SimpleModule {
    private static final long serialVersionUID = 1L;

    public LwnrdbModule(Optional<ZoneId> temporalZone) {
        super("lwnrdb");
        addSerializer(String.class, new TextSerializer());
        text(GeoPoint.class, CustomValues::format, CustomValues::parseGeo);
        text(Vector.class, CustomValues::format, CustomValues::parseVector);
        text(LocalDateTime.class, CustomValues::format, CustomValues::parseDateTime);
        text(LocalTime.class, CustomValues::format, CustomValues::parseTime);
        final var zone = new ZoneGate(temporalZone.orElse(null));
        text(Instant.class, instant -> CustomValues.format(LocalDateTime.ofInstant(instant, zone.require())),
                text -> CustomValues.parseDateTime(text).atZone(zone.require()).toInstant());
        text(OffsetDateTime.class,
                value -> CustomValues.format(value.atZoneSameInstant(zone.require()).toLocalDateTime()),
                text -> CustomValues.parseDateTime(text).atZone(zone.require()).toOffsetDateTime());
        text(ZonedDateTime.class,
                value -> CustomValues.format(value.withZoneSameInstant(zone.require()).toLocalDateTime()),
                text -> CustomValues.parseDateTime(text).atZone(zone.require()));
        addSerializer(Document.class, new DocumentSerializer());
        addDeserializer(Document.class, new DocumentDeserializer());
    }

    private <T> void text(Class<T> type, Function<T, String> format, Function<String, T> parse) {
        addSerializer(type, new CustomTextSerializer<>(type, format));
        addDeserializer(type, new CustomTextDeserializer<>(type, parse));
    }

    private record ZoneGate(ZoneId zone) {
        ZoneId require() {
            if (zone == null) {
                throw new IllegalArgumentException("zoned date-times need ClientSettings.temporalZone, because the"
                        + " server's datetime has no zone");
            }
            return zone;
        }
    }

    private static final class TextSerializer extends ValueSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializationContext context) {
            if (CustomValues.looksCustom(value)) {
                throw new IllegalArgumentException("field '" + gen.streamWriteContext().pathAsPointer()
                        + "' holds text the server would read as a custom value: " + value);
            }
            gen.writeString(value);
        }
    }

    private static final class CustomTextSerializer<T> extends ValueSerializer<T> {
        private final Class<T> type;
        private final Function<T, String> format;

        CustomTextSerializer(Class<T> type, Function<T, String> format) {
            this.type = type;
            this.format = format;
        }

        @Override
        public Class<?> handledType() {
            return type;
        }

        @Override
        public void serialize(T value, JsonGenerator gen, SerializationContext context) {
            gen.writeString(format.apply(value));
        }
    }

    private static final class CustomTextDeserializer<T> extends ValueDeserializer<T> {
        private final Class<T> type;
        private final Function<String, T> parse;

        CustomTextDeserializer(Class<T> type, Function<String, T> parse) {
            this.type = type;
            this.parse = parse;
        }

        @Override
        public Class<?> handledType() {
            return type;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) {
            return parse.apply(parser.getValueAsString());
        }
    }

    private static final class DocumentSerializer extends ValueSerializer<Document> {
        @Override
        public void serialize(Document value, JsonGenerator gen, SerializationContext context) {
            gen.writeTree(Documents.node(value));
        }
    }

    private static final class DocumentDeserializer extends ValueDeserializer<Document> {
        @Override
        public Document deserialize(JsonParser parser, DeserializationContext context) {
            final var tree = context.readTree(parser);
            if (!tree.isObject()) {
                throw new IllegalArgumentException("a document must be a JSON object");
            }
            return Documents.wrap((ObjectNode) tree);
        }
    }
}
