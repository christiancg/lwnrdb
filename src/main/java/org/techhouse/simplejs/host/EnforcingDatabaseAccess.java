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
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SchemaValidationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.ListCollectionsRequest;
import org.techhouse.ops.req.ListDatabasesRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.values.JsObject;

public final class EnforcingDatabaseAccess implements DatabaseAccess {
    private final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private final String username;
    private final UUID clientId;
    private JsObject errorPrototype;
    // Held for the transaction's lifetime rather than per dispatch: TransactionOperationHelper keys
    // purely on the client id, so a throwaway forwarded client would be unreachable on the next call.
    private UUID sessionClientId;
    // The only thread allowed to touch an open session — see DatabaseAccess.beginTransaction.
    private Thread sessionThread;

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
    public BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents) {
        final var request = new BulkSaveRequest(db, coll);
        request.setObjects(documents);
        final var response = dispatch(request);
        if (response instanceof BulkSaveResponse bulkSaveResponse) {
            return new BulkSaveOutcome(bulkSaveResponse.getInserted(), bulkSaveResponse.getUpdated());
        }
        // Unlike single save, a batch that was refused wholesale has no per-document result to fall
        // back to, so the rejection surfaces into the script instead of reading as "nothing changed".
        throw jsError(response.getMessage());
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

    @Override
    public void beginTransaction() {
        if (sessionClientId != null) {
            throw jsError("A transaction is already active on this script");
        }
        sessionThread = Thread.currentThread();
        sessionClientId = clientId != null ? clientId : clientTracker.registerForwardedClient(username);
        try {
            requireOk(dispatch(new StartTransactionRequest()));
        } catch (RuntimeException e) {
            clearSession();
            throw e;
        }
    }

    @Override
    public void commitTransaction() {
        finishTransaction(new CommitTransactionRequest());
    }

    @Override
    public void rollbackTransaction() {
        finishTransaction(new RollbackTransactionRequest());
    }

    private void finishTransaction(OperationRequest request) {
        assertSessionThread();
        if (sessionClientId == null) {
            throw jsError("No transaction is active on this script");
        }
        try {
            requireOk(dispatch(request));
        } finally {
            clearSession();
        }
    }

    private void clearSession() {
        if (sessionClientId != null && !sessionClientId.equals(clientId)) {
            clientTracker.removeById(sessionClientId);
        }
        sessionClientId = null;
        sessionThread = null;
    }

    private void requireOk(OperationResponse response) {
        if (response.getStatus() != OperationStatus.OK) {
            throw jsError(response.getMessage());
        }
    }

    // Releasing a collection write lock from a thread that does not own it silently does nothing and
    // strands it for the process's lifetime, so a cross-thread touch fails loudly instead.
    private void assertSessionThread() {
        if (sessionThread != null && sessionThread != Thread.currentThread()) {
            throw jsError("A script transaction may only be used from the thread that started it");
        }
    }

    private OperationResponse dispatch(OperationRequest request) {
        assertSessionThread();
        final var user = cache.getAdminUserEntry(username);
        if (user == null) {
            throw jsError("User '" + username + "' not found");
        }
        // The three transaction control requests carry no database or collection to authorize and are
        // absent from ALWAYS_ALLOWED_OPERATIONS, so AuthorizationChecker would deny every non-admin.
        // Each buffered write inside the transaction is still authorized on its own request.
        if (!isTransactionControl(request)) {
            final var authorization = AuthorizationChecker.check(request, user);
            if (!authorization.isAllowed()) {
                throw jsError(authorization.getReason());
            }
        }
        final var schemaError = SchemaValidationHelper.check(request);
        if (schemaError != null) {
            throw jsError(schemaError.getMessage());
        }
        if (sessionClientId != null) {
            return operationProcessor.processMessage(request, sessionClientId);
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

    private static boolean isTransactionControl(OperationRequest request) {
        return request instanceof StartTransactionRequest || request instanceof CommitTransactionRequest
                || request instanceof RollbackTransactionRequest;
    }

    @Override
    public void useErrorPrototype(JsObject prototype) {
        this.errorPrototype = prototype;
    }

    private JsThrowException jsError(String message) {
        return new JsThrowException(ErrorBuiltins.makeError("Error", message, errorPrototype));
    }
}
