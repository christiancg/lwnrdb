package org.techhouse.unit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.custom_types.GeoDistanceComparator;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.auth.AuthorizationChecker;

public class ProtocolCatalogueTest {
    private static final String WRITE_PROPERTY = "lwnrdb.protocol.regenerate";
    private static final EJson eJson = new EJson();
    private static final int CATALOGUE_LINE_CHARS = 128;

    private static String operationsCatalogue() {
        final var entries = new ArrayList<JsonObject>();
        for (final var type : ProtocolFiles.clientFacingOperations()) {
            final var entry = new JsonObject();
            entry.addProperty("name", type.name());
            entry.addProperty("scope", type.scope().name());
            entry.addProperty("adminOnly", AuthorizationChecker.isAdminOnly(type));
            entry.addProperty("allowedInTransaction", TransactionOperationHelper.isAllowedDuringTransaction(type));
            entries.add(entry);
        }
        return documentWithEntries("operations", entries, new JsonObject());
    }

    private static String errorCodesCatalogue() {
        final var entries = new ArrayList<JsonObject>();
        for (final var errorCode : ErrorCode.values()) {
            final var entry = new JsonObject();
            entry.addProperty("code", errorCode.getCode());
            entry.addProperty("name", errorCode.name());
            entry.addProperty("status", errorCode.getStatus().name());
            entry.add("message",
                    errorCode.getDefaultMessage() == null
                            ? JsonNull.INSTANCE
                            : new JsonString(errorCode.getDefaultMessage()));
            entry.addProperty("retryable", errorCode.isRetryable());
            entries.add(entry);
        }
        return documentWithEntries("errorCodes", entries, new JsonObject());
    }

    private static String customTypesCatalogue() throws ReflectiveOperationException {
        final var instances = new TreeMap<String, JsonCustom<?>>();
        for (final var type : CustomTypeFactory.getCustomTypes().values()) {
            final JsonCustom<?> instance = type.getConstructor().newInstance();
            instances.put(instance.getCustomTypeName(), instance);
        }
        final var entries = new ArrayList<JsonObject>();
        for (final var instance : instances.values()) {
            final var entry = new JsonObject();
            entry.addProperty("name", instance.getCustomTypeName());
            entry.add("predicateOperators", sortedArray(instance.customOperatorNames()));
            entry.add("rankingOperators", sortedArray(instance.customRankingOperatorNames()));
            entries.add(entry);
        }
        final var header = new JsonObject();
        header.addProperty("detectionRegex", Globals.CUSTOM_JSON_REGEX);
        header.add("geoDistanceComparators",
                sortedArray(Arrays.stream(GeoDistanceComparator.values()).map(Enum::name).toList()));
        return documentWithEntries("types", entries, header);
    }

    private static JsonArray sortedArray(java.util.Collection<String> values) {
        final var array = new JsonArray();
        new TreeSet<>(values).forEach(array::add);
        return array;
    }

    private static String documentWithEntries(String arrayName, List<JsonObject> entries, JsonObject header) {
        final var text = new StringBuilder(CATALOGUE_LINE_CHARS * (entries.size() + header.size() + 4)).append("{\n");
        for (final var member : header.entrySet()) {
            text.append("  \"").append(member.getKey()).append("\": ").append(eJson.toJson(member.getValue()))
                    .append(",\n");
        }
        text.append("  \"").append(arrayName).append("\": [\n");
        for (var i = 0; i < entries.size(); i++) {
            text.append("    ").append(eJson.toJson(entries.get(i))).append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        return text.append("  ]\n}\n").toString();
    }

    private static void assertCatalogue(Path file, String expected) throws IOException {
        if (Boolean.getBoolean(WRITE_PROPERTY)) {
            Files.createDirectories(ProtocolFiles.ROOT);
            Files.writeString(file, expected, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(expected, Files.readString(file, StandardCharsets.UTF_8),
                file + " is stale; rerun with -D" + WRITE_PROPERTY + "=true");
    }

    @Test
    public void test_operations_catalogue_matches_operation_type() throws IOException {
        assertCatalogue(ProtocolFiles.OPERATIONS, operationsCatalogue());
    }

    @Test
    public void test_error_catalogue_matches_error_code() throws IOException {
        assertCatalogue(ProtocolFiles.ERROR_CODES, errorCodesCatalogue());
    }

    @Test
    public void test_custom_types_catalogue_matches_the_registered_types() throws Exception {
        assertCatalogue(ProtocolFiles.CUSTOM_TYPES, customTypesCatalogue());
    }

    @Test
    public void test_non_client_operations_are_excluded() {
        final var names = ProtocolFiles.clientFacingOperations().stream().map(Enum::name).toList();
        for (final var nonClient : ProtocolFiles.NON_CLIENT_OPERATIONS) {
            assertFalse(names.contains(nonClient.name()), nonClient.name());
        }
        assertEquals(OperationType.values().length - ProtocolFiles.NON_CLIENT_OPERATIONS.size(), names.size());
    }

    @Test
    public void test_retryable_codes_come_from_globals() {
        final var retryable = new TreeSet<String>();
        for (final var errorCode : ErrorCode.values()) {
            if (errorCode.isRetryable()) {
                retryable.add(errorCode.getCode());
            }
        }
        assertEquals(new TreeSet<>(Globals.RETRYABLE_ERROR_CODES), retryable);
        for (final var code : Globals.RETRYABLE_ERROR_CODES) {
            assertNotNull(ErrorCode.byCode(code), code);
        }
    }

    @Test
    public void test_an_unknown_outcome_and_a_committed_write_are_never_retryable() {
        final Map<String, ErrorCode> never = Map.of("500-33", ErrorCode.byCode("500-33"), "500-34",
                ErrorCode.byCode("500-34"), "503-3", ErrorCode.byCode("503-3"));
        never.forEach((code, errorCode) -> {
            if (errorCode == null) {
                fail(code + " is not an error code");
            }
            assertFalse(errorCode.isRetryable(), code);
        });
    }
}
