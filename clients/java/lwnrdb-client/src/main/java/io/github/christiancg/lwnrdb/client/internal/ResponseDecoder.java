package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ConflictException;
import io.github.christiancg.lwnrdb.client.error.ForbiddenException;
import io.github.christiancg.lwnrdb.client.error.ListenEndedException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.error.UnavailableException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class ResponseDecoder {
    private static final Set<Status> FAILURES = Set.of(Status.ERROR, Status.NOT_FOUND, Status.UNAUTHENTICATED,
            Status.FORBIDDEN);
    private static final Set<String> UNKNOWN_OUTCOME = Set.of(ErrorCode.TRANSACTION_HALF_APPLIED.code(),
            ErrorCode.WRITE_LOGGED_NOT_APPLIED.code());

    private ResponseDecoder() {
    }

    public static ObjectNode check(ObjectNode frame) {
        final var status = statusOf(frame);
        if (status != null && FAILURES.contains(status)) {
            throw raise(frame);
        }
        return frame;
    }

    public static LwnrdbException raise(ObjectNode frame) {
        final var code = textOrNull(frame.get("errorCode"));
        final var status = statusOf(frame);
        final var message = Optional.ofNullable(textOrNull(frame.get("message")))
                .orElseGet(() -> ErrorCode.fromCode(code).flatMap(ErrorCode::defaultMessage).orElse("request failed"));
        final var text = code == null ? message : message + " (" + code + ")";
        final var range = code == null || code.length() < 3 ? "" : code.substring(0, 3);
        if (code != null && UNKNOWN_OUTCOME.contains(code)) {
            return new UnknownOutcomeException(text, code, status);
        }
        return switch (range) {
            case "401" -> new AuthenticationException(text, code, status);
            case "403" -> new ForbiddenException(text, code, status);
            case "404" -> new NotFoundException(text, code, status);
            case "409" -> new ConflictException(text, code, status);
            case "410" -> new ListenEndedException(text, code, status);
            case "503" -> new UnavailableException(text, code, status);
            default -> byStatus(text, code, status);
        };
    }

    public static List<Document> documents(ObjectNode frame, String field) {
        final var node = frame.get(field);
        final var documents = new ArrayList<Document>();
        if (node != null && node.isArray()) {
            for (final var item : node.values()) {
                if (item.isObject()) {
                    documents.add(Documents.wrap((ObjectNode) item));
                }
            }
        }
        return documents;
    }

    public static Optional<Document> document(ObjectNode frame, String field) {
        final var node = frame.get(field);
        return node != null && node.isObject() ? Optional.of(Documents.wrap((ObjectNode) node)) : Optional.empty();
    }

    public static List<String> strings(JsonNode node) {
        final var strings = new ArrayList<String>();
        if (node != null && node.isArray()) {
            node.values().forEach(item -> strings.add(item.asString()));
        }
        return strings;
    }

    public static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static Status statusOf(ObjectNode frame) {
        final var status = textOrNull(frame.get("status"));
        if (status == null) {
            return null;
        }
        try {
            return Status.valueOf(status);
        } catch (IllegalArgumentException e) {
            return Status.ERROR;
        }
    }

    private static LwnrdbException byStatus(String text, String code, Status status) {
        if (status == null) {
            return new LwnrdbException(text, code, null);
        }
        return switch (status) {
            case UNAUTHENTICATED -> new AuthenticationException(text, code, status);
            case FORBIDDEN -> new ForbiddenException(text, code, status);
            case NOT_FOUND -> new NotFoundException(text, code, status);
            default -> new LwnrdbException(text, code, status);
        };
    }
}
