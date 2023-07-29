package org.techhouse.ops.req;

import com.google.gson.Gson;
import org.techhouse.ex.InvalidCommandException;

public class RequestParser {
    private static final Gson gson = new Gson();

    public static OperationRequest parseRequest(String message) throws InvalidCommandException {
        try {
            final var baseReq = gson.fromJson(message, OperationRequest.class);
            return switch (baseReq.getType()) {
                case SAVE -> {
                    final var parsed = gson.fromJson(message, SaveRequest.class);
                    if(parsed.getObject().has("_id")) {
                        parsed.set_id(parsed.getObject().get("_id").getAsString());
                    }
                    yield parsed;
                }
                case FIND -> gson.fromJson(message, FindRequest.class);
                case AGGREGATE -> gson.fromJson(message, AggregateRequest.class);
                case DELETE -> gson.fromJson(message, DeleteRequest.class);
                case CREATE_DATABASE -> gson.fromJson(message, CreateDatabaseRequest.class);
                case CREATE_COLLECTION -> gson.fromJson(message, CreateCollectionRequest.class);
            };
        } catch (Exception e) {
            throw new InvalidCommandException(e);
        }
    }
}
