package org.techhouse.simplejs.host;

import java.util.List;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;

public final class EnforcingDatabaseAccess implements DatabaseAccess {
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final String username;
    private final UUID clientId;

    public EnforcingDatabaseAccess(String username, UUID clientId) {
        this.username = username;
        this.clientId = clientId;
    }

    @Override
    public JsonObject findById(String db, String coll, String id) {
        final var request = new FindByIdRequest(db, coll);
        request.set_id(id);
        final var response = dispatch(request);
        if (response instanceof FindByIdResponse findByIdResponse) {
            return findByIdResponse.getObject();
        }
        return null;
    }

    @Override
    public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
        final var message = new JsonObject();
        message.add("type", new JsonString("AGGREGATE"));
        message.add("databaseName", new JsonString(db));
        message.add("collectionName", new JsonString(coll));
        message.add("aggregationSteps", pipeline);
        final OperationRequest request = RequestParser.parseRequest(eJson.toJson(message));
        final var response = dispatch(request);
        if (response instanceof AggregateResponse aggregateResponse) {
            return aggregateResponse.getResults();
        }
        return List.of();
    }

    @Override
    public JsonObject save(String db, String coll, JsonObject document) {
        final var request = new SaveRequest(db, coll);
        request.setObject(document);
        if (document.has(Globals.PK_FIELD)) {
            request.set_id(document.get(Globals.PK_FIELD).asJsonString().getValue());
        }
        final var response = dispatch(request);
        if (response instanceof SaveResponse saveResponse) {
            return findById(db, coll, saveResponse.get_id());
        }
        return null;
    }

    @Override
    public void delete(String db, String coll, String id) {
        final var request = new DeleteRequest(db, coll);
        request.set_id(id);
        dispatch(request);
    }

    @Override
    public List<String> listCollections(String db) {
        final var response = dispatch(new ListCollectionsRequest(db));
        if (response instanceof ListCollectionsResponse listCollectionsResponse) {
            return listCollectionsResponse.getCollections();
        }
        return List.of();
    }

    @Override
    public List<String> listDatabases() {
        final var response = dispatch(new ListDatabasesRequest());
        if (response instanceof ListDatabasesResponse listDatabasesResponse) {
            return listDatabasesResponse.getDatabases();
        }
        return List.of();
    }

    private OperationResponse dispatch(OperationRequest request) {
        final var user = cache.getAdminUserEntry(username);
        if (user == null) {
            throw jsError("User '" + username + "' not found");
        }
        final var authorization = AuthorizationChecker.check(request, user);
        if (!authorization.isAllowed()) {
            throw jsError(authorization.getReason());
        }
        final var schemaError = SchemaValidationHelper.check(request);
        if (schemaError != null) {
            throw jsError(schemaError.getMessage());
        }
        if (clientId != null) {
            return operationProcessor.processMessage(request, clientId);
        }
        final var forwardedClientId = clientTracker.registerForwardedClient(username);
        try {
            return operationProcessor.processMessage(request, forwardedClientId);
        } finally {
            clientTracker.removeById(forwardedClientId);
        }
    }

    private JsThrowException jsError(String message) {
        return new JsThrowException(ErrorBuiltins.makeError("Error", message));
    }
}
