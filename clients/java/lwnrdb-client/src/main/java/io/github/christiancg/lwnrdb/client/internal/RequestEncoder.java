package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.query.Filter;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class RequestEncoder {
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    static final String DATABASE = "databaseName";
    static final String COLLECTION = "collectionName";
    static final String STEPS = "aggregationSteps";
    private static final String ID = "_id";

    private final PipelineEncoder pipeline;

    public RequestEncoder(Json json) {
        this.pipeline = new PipelineEncoder(json);
    }

    public PipelineEncoder pipeline() {
        return pipeline;
    }

    static ObjectNode of(Operation operation) {
        return NODES.objectNode().put("type", operation.name());
    }

    static ObjectNode of(Operation operation, String database) {
        return of(operation).put(DATABASE, database);
    }

    static ObjectNode of(Operation operation, String database, String collection) {
        return of(operation, database).put(COLLECTION, collection);
    }

    public ObjectNode authenticate(String username, String password) {
        return of(Operation.AUTHENTICATE).put("username", username).put("password", password);
    }

    public ObjectNode save(String database, String collection, Document document) {
        return of(Operation.SAVE, database, collection).set("object", Documents.node(document));
    }

    public ObjectNode insert(String database, String collection, Document document) {
        return of(Operation.SAVE, database, collection).put("failOnUpdate", true).set("object",
                Documents.node(document));
    }

    public ObjectNode updateWhere(String database, String collection, Filter condition, Document patch) {
        return of(Operation.SAVE, database, collection).set("condition", pipeline.filter(condition)).set("object",
                Documents.node(patch));
    }

    public ObjectNode bulkSave(String database, String collection, List<Document> documents) {
        final var request = of(Operation.BULK_SAVE, database, collection);
        final var objects = request.putArray("objects");
        documents.forEach(document -> objects.add(Documents.node(document)));
        return request;
    }

    public ObjectNode findById(String database, String collection, String id, boolean dirtyRead) {
        return dirty(of(Operation.FIND_BY_ID, database, collection).put(ID, id), dirtyRead);
    }

    public ObjectNode delete(String database, String collection, String id) {
        return of(Operation.DELETE, database, collection).put(ID, id);
    }

    public ObjectNode deleteWhere(String database, String collection, Filter condition) {
        return of(Operation.DELETE, database, collection).set("condition", pipeline.filter(condition));
    }

    public ObjectNode aggregate(String database, String collection, List<PipelineStep> steps, boolean dirtyRead,
            boolean analyze) {
        final var request = of(Operation.AGGREGATE, database, collection);
        request.set(STEPS, pipeline.steps(steps));
        if (analyze) {
            request.put("analyze", true);
        }
        return dirty(request, dirtyRead);
    }

    public ObjectNode listen(String database, String collection, List<PipelineStep> steps) {
        return of(Operation.LISTEN, database, collection).set(STEPS, pipeline.steps(steps));
    }

    public ObjectNode stopListen(String listenId) {
        return of(Operation.STOP_LISTEN).put("listenId", listenId);
    }

    public ObjectNode listDatabases() {
        return of(Operation.LIST_DATABASES);
    }

    public ObjectNode createDatabase(String database) {
        return of(Operation.CREATE_DATABASE, database);
    }

    public ObjectNode dropDatabase(String database) {
        return of(Operation.DROP_DATABASE, database);
    }

    public ObjectNode listCollections(String database) {
        return of(Operation.LIST_COLLECTIONS, database);
    }

    public ObjectNode createCollection(String database, String collection) {
        return of(Operation.CREATE_COLLECTION, database, collection);
    }

    public ObjectNode dropCollection(String database, String collection) {
        return of(Operation.DROP_COLLECTION, database, collection);
    }

    public ObjectNode createIndex(String database, String collection, List<String> fields) {
        final var request = of(Operation.CREATE_INDEX, database, collection);
        if (fields.size() == 1) {
            return request.put("fieldName", fields.get(0));
        }
        final var names = request.putArray("fieldNames");
        fields.forEach(names::add);
        return request;
    }

    public ObjectNode dropIndex(String database, String collection, String field) {
        return of(Operation.DROP_INDEX, database, collection).put("fieldName", field);
    }

    public ObjectNode reindex(String database, String collection, List<String> fields) {
        final var request = of(Operation.REINDEX, database, collection);
        if (!fields.isEmpty()) {
            final var names = request.putArray("fieldNames");
            fields.forEach(names::add);
        }
        return request;
    }

    public ObjectNode reindexPrimaryKey(String database, String collection) {
        return of(Operation.REINDEX, database, collection).put("primaryKey", true);
    }

    public ObjectNode saveSchema(String database, String collection, Document schema) {
        return of(Operation.SAVE_SCHEMA, database, collection).set("schema", Documents.node(schema));
    }

    public ObjectNode getSchema(String database, String collection) {
        return of(Operation.GET_SCHEMA, database, collection);
    }

    public ObjectNode deleteSchema(String database, String collection) {
        return of(Operation.DELETE_SCHEMA, database, collection);
    }

    public ObjectNode startTransaction() {
        return of(Operation.START_TRANSACTION);
    }

    public ObjectNode commitTransaction() {
        return of(Operation.COMMIT_TRANSACTION);
    }

    public ObjectNode rollbackTransaction() {
        return of(Operation.ROLLBACK_TRANSACTION);
    }

    public ObjectNode serverInfo() {
        return of(Operation.SERVER_INFO);
    }

    public ObjectNode databaseStats() {
        return of(Operation.GET_DATABASE_STATS);
    }

    public ObjectNode databaseStats(String database) {
        return of(Operation.GET_DATABASE_STATS, database);
    }

    public ObjectNode closeConnection() {
        return of(Operation.CLOSE_CONNECTION);
    }

    public ObjectNode setDatabaseOwners(String database, List<String> owners) {
        final var request = of(Operation.SET_DATABASE_OWNERS, database);
        final var array = request.putArray("owners");
        owners.forEach(array::add);
        return request;
    }

    private static ObjectNode dirty(ObjectNode request, boolean dirtyRead) {
        return dirtyRead ? request.put("dirtyRead", true) : request;
    }
}
