package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import tools.jackson.databind.node.ObjectNode;

public final class Indexes {
    private final ClientContext context;
    private final String database;
    private final String collection;

    Indexes(ClientContext context, String database, String collection) {
        this.context = context;
        this.database = database;
        this.collection = collection;
    }

    public void create(String field, String... moreFields) {
        final var fields = new ArrayList<String>();
        fields.add(Objects.requireNonNull(field, "field"));
        fields.addAll(Arrays.asList(moreFields));
        context.channel().send(Operation.CREATE_INDEX, context.encoder().createIndex(database, collection, fields));
    }

    public void drop(String field) {
        context.channel().send(Operation.DROP_INDEX, context.encoder().dropIndex(database, collection, field));
    }

    public ReindexResult reindex(String... fields) {
        return result(context.channel().send(Operation.REINDEX,
                context.encoder().reindex(database, collection, List.of(fields))));
    }

    public ReindexResult reindexPrimaryKey() {
        return result(
                context.channel().send(Operation.REINDEX, context.encoder().reindexPrimaryKey(database, collection)));
    }

    private static ReindexResult result(ObjectNode reply) {
        return new ReindexResult(ResponseDecoder.strings(reply.get("rebuiltFields")),
                optionalLong(reply, "primaryKeyEntries"), optionalLong(reply, "unversionedEntries"));
    }

    private static OptionalLong optionalLong(ObjectNode reply, String field) {
        final var node = reply.get(field);
        return node == null || !node.isNumber() ? OptionalLong.empty() : OptionalLong.of(node.longValue());
    }
}
