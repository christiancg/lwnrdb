package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.AnalyzeResult;
import io.github.christiancg.lwnrdb.client.BulkSaveResult;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import io.github.christiancg.lwnrdb.client.SaveResult;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.mapping.JacksonCodec;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.query.Filter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class CollectionOps<T> implements QueryTarget<T> {
    private final ClientContext context;
    private final RequestChannel channel;
    private final String database;
    private final String collection;
    private final DocumentCodec<T> codec;

    public CollectionOps(ClientContext context, RequestChannel channel, String database, String collection,
            DocumentCodec<T> codec) {
        this.context = context;
        this.channel = channel;
        this.database = Names.database(database);
        this.collection = Names.collection(collection);
        this.codec = codec;
    }

    public String database() {
        return database;
    }

    public String collection() {
        return collection;
    }

    public ClientContext context() {
        return context;
    }

    @Override
    public DocumentCodec<T> codec() {
        return codec;
    }

    public SaveResult save(T value) {
        final var document = codec.encode(value);
        document.id().ifPresent(Names::id);
        final var reply = channel.send(Operation.SAVE, context.encoder().save(database, collection, document));
        return new SaveResult(ResponseDecoder.textOrNull(reply.get("_id")), reply.path("inserted").asBoolean(false));
    }

    public BulkSaveResult saveAll(List<T> values) {
        final var documents = values.stream().map(codec::encode).toList();
        documents.forEach(document -> document.id().ifPresent(Names::id));
        final var reply = channel.send(Operation.BULK_SAVE,
                context.encoder().bulkSave(database, collection, documents));
        return new BulkSaveResult(ResponseDecoder.strings(reply.get("inserted")),
                ResponseDecoder.strings(reply.get("updated")));
    }

    public Optional<T> findById(String id, boolean dirtyRead) {
        try {
            final var reply = channel.send(Operation.FIND_BY_ID,
                    context.encoder().findById(database, collection, Names.id(id), dirtyRead));
            return ResponseDecoder.document(reply, "object").map(codec::decode);
        } catch (NotFoundException e) {
            if (e.hasCode(ErrorCode.ENTRY_NOT_FOUND)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void delete(String id) {
        channel.send(Operation.DELETE, context.encoder().delete(database, collection, Names.id(id)));
    }

    public long deleteWhere(Filter condition) {
        final var reply = channel.send(Operation.DELETE,
                context.encoder().deleteWhere(database, collection, Objects.requireNonNull(condition, "condition")));
        return reply.path("deletedCount").asLong(0);
    }

    public long updateWhere(Filter condition, Object patch) {
        final var request = context.encoder().updateWhere(database, collection,
                Objects.requireNonNull(condition, "condition"), patchOf(Objects.requireNonNull(patch, "patch")));
        return channel.send(Operation.SAVE, request).path("updatedCount").asLong(0);
    }

    private Document patchOf(Object patch) {
        return patch instanceof Document document ? document : encode(patch.getClass(), patch);
    }

    private <P> Document encode(Class<P> type, Object patch) {
        return new JacksonCodec<>(type, context.json()).encode(type.cast(patch));
    }

    @Override
    public List<Document> aggregate(List<PipelineStep> steps, boolean dirtyRead) {
        try {
            final var reply = channel.send(Operation.AGGREGATE,
                    context.encoder().aggregate(database, collection, steps, dirtyRead, false));
            return ResponseDecoder.documents(reply, "results");
        } catch (NotFoundException e) {
            if (e.hasCode(ErrorCode.NO_RESULTS)) {
                return List.of();
            }
            throw e;
        }
    }

    @Override
    public AnalyzeResult analyze(List<PipelineStep> steps, boolean dirtyRead) {
        final var reply = channel.send(Operation.AGGREGATE,
                context.encoder().aggregate(database, collection, steps, dirtyRead, true));
        return new AnalyzeResult(ResponseDecoder.documents(reply, "results"),
                ResponseDecoder.document(reply, "analyzeResult").orElseGet(Document::empty));
    }
}
