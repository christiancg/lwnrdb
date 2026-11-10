package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.ClientSettings;
import io.github.christiancg.lwnrdb.client.error.MappingException;
import io.github.christiancg.lwnrdb.client.mapping.LwnrdbModule;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class Json {
    public static final int MAX_REPLY_DEPTH = 512;
    public static final long DEFAULT_MAX_REPLY_BYTES = 256L * 1024 * 1024;
    private static final Json DEFAULTS = new Json(DEFAULT_MAX_REPLY_BYTES);

    private final JsonMapper frames;
    private final JsonMapper values;

    public Json(long maxReplyBytes) {
        this(JsonMapper.builder(), maxReplyBytes, new LwnrdbModule());
    }

    public Json(ClientSettings settings) {
        this(settings.objectMapper().map(JsonMapper::rebuild).orElseGet(JsonMapper::builder), settings.maxReplyBytes(),
                settings.temporalZone().map(LwnrdbModule::new).orElseGet(LwnrdbModule::new));
    }

    private Json(JsonMapper.Builder values, long maxReplyBytes, LwnrdbModule module) {
        final var constraints = StreamReadConstraints.builder().maxNestingDepth(MAX_REPLY_DEPTH)
                .maxStringLength((int) Math.min(maxReplyBytes, Integer.MAX_VALUE)).maxDocumentLength(maxReplyBytes)
                .build();
        this.frames = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build()).build();
        this.values = values.addModule(module).build();
    }

    public static Json defaults() {
        return DEFAULTS;
    }

    public ObjectNode parseFrame(byte[] bytes, int offset, int length) {
        final var node = frames.readTree(bytes, offset, length);
        if (node == null || !node.isObject()) {
            throw new MappingException("a reply frame must be a JSON object", null);
        }
        return (ObjectNode) node;
    }

    public ObjectNode parseObject(String json) {
        final JsonNode node;
        try {
            node = frames.readTree(json);
        } catch (JacksonException e) {
            throw new MappingException("not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new MappingException("expected a JSON object", null);
        }
        return (ObjectNode) node;
    }

    public byte[] encodeFrame(ObjectNode frame) {
        NumberGuard.check(frame);
        return frames.writeValueAsBytes(frame);
    }

    public String text(JsonNode node) {
        return frames.writeValueAsString(node);
    }

    public JsonNode toNode(Object value) {
        try {
            return values.valueToTree(value);
        } catch (JacksonException e) {
            throw unwrap(e);
        }
    }

    public <T> T fromNode(JsonNode node, Class<T> type) {
        try {
            return values.treeToValue(node, type);
        } catch (JacksonException e) {
            throw unwrap(e);
        }
    }

    public Object toPlain(JsonNode node) {
        return frames.convertValue(node, Object.class);
    }

    public JsonMapper values() {
        return values;
    }

    private static RuntimeException unwrap(JacksonException e) {
        if (e.getCause() instanceof IllegalArgumentException argument) {
            return argument;
        }
        return new MappingException(e.getOriginalMessage(), e);
    }
}
