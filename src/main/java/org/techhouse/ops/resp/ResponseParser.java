package org.techhouse.ops.resp;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

/**
 * Rebuilds a typed {@link OperationResponse} from the response JSON a peer node returned, mirroring
 * {@code ops/req/RequestParser} on the response side. Each subclass is constructed through its public
 * constructor rather than by reflection: the four fields inherited from {@link OperationResponse} are
 * final with no matching constructor arity, so the reflective path would fall through to unsafe
 * allocation.
 */
public final class ResponseParser {
    private ResponseParser() {
    }

    private static final EJson eJson = IocContainer.get(EJson.class);

    public static OperationResponse parseResponse(final String json) {
        final var object = eJson.fromJson(json, JsonObject.class);
        final var type = OperationType.valueOf(text(object, "type"));
        final var status = OperationStatus.valueOf(text(object, "status"));
        final var message = text(object, "message");
        if (status != OperationStatus.OK) {
            final var errorCode = ErrorCode.byCode(text(object, "errorCode"));
            return errorCode != null
                    ? new OperationResponse(type, message, errorCode)
                    : new OperationResponse(type, status, message);
        }
        return switch (type) {
            case FIND_BY_ID -> new FindByIdResponse(message, document(object));
            case AGGREGATE -> new AggregateResponse(message, results(object));
            case SAVE -> new SaveResponse(message, text(object, "_id"));
            case BULK_SAVE -> new BulkSaveResponse(message, strings(object, "inserted"), strings(object, "updated"));
            case DELETE -> new DeleteResponse(message);
            case LIST_COLLECTIONS -> new ListCollectionsResponse(message, strings(object, "collections"));
            case LIST_DATABASES -> new ListDatabasesResponse(message, strings(object, "databases"));
            case START_TRANSACTION -> new StartTransactionResponse(message, text(object, "transactionId"));
            case COMMIT_TRANSACTION -> new CommitTransactionResponse(message);
            case ROLLBACK_TRANSACTION -> new RollbackTransactionResponse(message);
            default -> new OperationResponse(type, status, message);
        };
    }

    private static String text(final JsonObject object, final String field) {
        final var value = value(object, field);
        return value == null ? null : value.asJsonString().getValue();
    }

    private static JsonObject document(final JsonObject object) {
        final var value = value(object, "object");
        return value == null ? null : value.asJsonObject();
    }

    private static List<String> strings(final JsonObject object, final String field) {
        final var result = new ArrayList<String>();
        for (final var element : array(object, field)) {
            result.add(element.asJsonString().getValue());
        }
        return result;
    }

    private static List<JsonObject> results(final JsonObject object) {
        final var list = new ArrayList<JsonObject>();
        for (final var element : array(object, "results")) {
            list.add(element.asJsonObject());
        }
        return list;
    }

    private static JsonArray array(final JsonObject object, final String field) {
        final var value = value(object, field);
        return value == null ? new JsonArray() : value.asJsonArray();
    }

    private static JsonBaseElement value(final JsonObject object, final String field) {
        if (!object.has(field)) {
            return null;
        }
        final var value = object.get(field);
        return value == null || value.getJsonType() == JsonBaseElement.JsonType.NULL ? null : value;
    }
}
