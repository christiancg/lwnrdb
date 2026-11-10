package org.techhouse.unit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.GeoDistanceComparator;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;

public class ClientFixturesTest {
    private static final EJson eJson = new EJson();
    private static final String UNKNOWN_CODE_CASE = "errors.unknown_code";
    private static final List<String> FIXTURE_USERS = List.of("alice", "bob");
    private static final Cache cache = IocContainer.get(Cache.class);

    private record FixtureCase(String file, String name, JsonObject body) {
    }

    @BeforeAll
    public static void seedFixtureUsers() {
        for (final var username : FIXTURE_USERS) {
            cache.putAdminUserEntry(
                    new AdminUserEntry(username, "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>()),
                    new PkIndexEntry(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, username, 0, 10, 0));
        }
    }

    @AfterAll
    public static void removeFixtureUsers() {
        FIXTURE_USERS.forEach(cache::removeAdminUserEntry);
    }

    private static List<FixtureCase> casesIn(Path folder) throws IOException {
        final var cases = new ArrayList<FixtureCase>();
        try (Stream<Path> files = Files.list(folder)) {
            for (final var file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                final var document = eJson.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                for (final var element : document.get("cases").asJsonArray().asList()) {
                    final var entry = element.asJsonObject();
                    cases.add(new FixtureCase(String.valueOf(file.getFileName()),
                            entry.get("name").asJsonString().getValue(), entry));
                }
            }
        }
        return cases;
    }

    private static JsonObject requestOf(FixtureCase fixtureCase) {
        return fixtureCase.body().get("request").asJsonObject();
    }

    private static String typeOf(JsonObject message) {
        return message.get("type").asJsonString().getValue();
    }

    private static List<JsonObject> requests() throws IOException {
        return casesIn(ProtocolFiles.REQUEST_FIXTURES).stream().map(ClientFixturesTest::requestOf).toList();
    }

    private static Set<String> customOperatorsUsed() throws IOException {
        final var used = new TreeSet<String>();
        for (final var request : requests()) {
            collectCustomOperators(request, used);
        }
        return used;
    }

    private static void collectCustomOperators(JsonBaseElement element, Set<String> used) {
        if (element.isJsonObject()) {
            final var object = element.asJsonObject();
            final var name = object.get("customOperatorName");
            if (name != null) {
                final var comparator = object.get("comparator");
                used.add(name.asJsonString().getValue()
                        + (comparator == null ? "" : ":" + comparator.asJsonString().getValue()));
            }
            object.entrySet().forEach(member -> collectCustomOperators(member.getValue(), used));
        } else if (element.isJsonArray()) {
            element.asJsonArray().asList().forEach(item -> collectCustomOperators(item, used));
        }
    }

    private static boolean savesCustomType(JsonBaseElement element, String typeName) {
        if (element.isJsonString()) {
            return element.asJsonString().getValue().startsWith("#" + typeName + "(");
        }
        if (element.isJsonObject()) {
            return element.asJsonObject().entrySet().stream()
                    .anyMatch(member -> savesCustomType(member.getValue(), typeName));
        }
        if (element.isJsonArray()) {
            return element.asJsonArray().asList().stream().anyMatch(item -> savesCustomType(item, typeName));
        }
        return false;
    }

    @TestFactory
    public Stream<DynamicTest> test_every_request_fixture_parses_and_validates() throws IOException {
        return casesIn(ProtocolFiles.REQUEST_FIXTURES).stream()
                .map(fixtureCase -> DynamicTest.dynamicTest(fixtureCase.name(), () -> {
                    final var request = requestOf(fixtureCase);
                    final var parsed = RequestParser.parseRequest(eJson.toJson(request));
                    assertEquals(typeOf(request), parsed.getType().name(), fixtureCase.name());
                    final var validation = RequestValidator.validate(parsed);
                    assertTrue(validation.isValid(), fixtureCase.name() + ": " + validation.getErrorMessage());
                }));
    }

    @Test
    public void test_every_client_facing_operation_has_a_fixture() throws IOException {
        final var covered = new HashSet<String>();
        requests().forEach(request -> covered.add(typeOf(request)));
        for (final var type : ProtocolFiles.clientFacingOperations()) {
            assertTrue(covered.contains(type.name()), type.name() + " has no request fixture");
        }
    }

    @Test
    public void test_no_fixture_uses_a_test_only_operation() throws IOException {
        for (final var request : requests()) {
            final var type = OperationType.valueOf(typeOf(request));
            assertFalse(ProtocolFiles.TEST_ONLY_OPERATIONS.contains(type), type.name());
        }
    }

    @Test
    public void test_every_custom_operator_and_type_has_a_fixture() throws Exception {
        final var used = customOperatorsUsed();
        assertTrue(used.contains("within"), "within");
        assertTrue(used.contains("nearest"), "nearest");
        for (final var comparator : GeoDistanceComparator.values()) {
            assertTrue(used.contains("distance:" + comparator.name()), comparator.name());
        }
        final var saves = requests().stream().filter(request -> typeOf(request).equals("SAVE")).toList();
        for (final var typeName : List.of("datetime", "time", "geo", "vector")) {
            assertTrue(saves.stream().anyMatch(save -> savesCustomType(save.get("object"), typeName)), typeName);
        }
    }

    @Test
    public void test_response_fixtures_name_known_operations_statuses_and_codes() throws IOException {
        for (final var fixtureCase : casesIn(ProtocolFiles.RESPONSE_FIXTURES)) {
            final var frame = fixtureCase.body().get("frame").asJsonObject();
            final var type = frame.get("type");
            if (type != null && !type.isJsonNull()) {
                assertNotNull(OperationType.valueOf(type.asJsonString().getValue()), fixtureCase.name());
            }
            assertNotNull(OperationStatus.valueOf(frame.get("status").asJsonString().getValue()), fixtureCase.name());
            final var errorCode = frame.get("errorCode");
            if (errorCode != null && !errorCode.isJsonNull() && !UNKNOWN_CODE_CASE.equals(fixtureCase.name())) {
                final var known = ErrorCode.byCode(errorCode.asJsonString().getValue());
                assertNotNull(known, fixtureCase.name());
                assertEquals(known.getStatus().name(), frame.get("status").asJsonString().getValue(),
                        fixtureCase.name());
            }
            assertNotNull(fixtureCase.body().get("expect"), fixtureCase.name());
        }
    }

    @Test
    public void test_fixture_names_are_unique() throws IOException {
        final var names = new HashSet<String>();
        for (final var folder : List.of(ProtocolFiles.REQUEST_FIXTURES, ProtocolFiles.RESPONSE_FIXTURES)) {
            for (final var fixtureCase : casesIn(folder)) {
                assertTrue(names.add(fixtureCase.name()), fixtureCase.file() + ": " + fixtureCase.name());
            }
        }
    }
}
