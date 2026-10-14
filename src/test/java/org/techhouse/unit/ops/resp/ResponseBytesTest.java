package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeResult;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.PkRebuild;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.AggregateAnalyzeResponse;
import org.techhouse.ops.resp.AggregateResponse;
import org.techhouse.ops.resp.BulkSaveResponse;
import org.techhouse.ops.resp.CallProcedureResponse;
import org.techhouse.ops.resp.CancelScriptResponse;
import org.techhouse.ops.resp.CloseConnectionResponse;
import org.techhouse.ops.resp.DeleteResponse;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.GetDatabaseStatsResponse;
import org.techhouse.ops.resp.ListCollectionsResponse;
import org.techhouse.ops.resp.ListDatabasesResponse;
import org.techhouse.ops.resp.ListProceduresResponse;
import org.techhouse.ops.resp.ListSchedulesResponse;
import org.techhouse.ops.resp.ListScriptsResponse;
import org.techhouse.ops.resp.ListTransactionsResponse;
import org.techhouse.ops.resp.ListTriggerRunsResponse;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.ListUsersResponse;
import org.techhouse.ops.resp.ListenEndedResponse;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.PrimaryKeyReindexResponse;
import org.techhouse.ops.resp.ReindexResponse;
import org.techhouse.ops.resp.ResolveTriggerRunResponse;
import org.techhouse.ops.resp.RunScriptResponse;
import org.techhouse.ops.resp.SaveProcedureResponse;
import org.techhouse.ops.resp.SaveResponse;
import org.techhouse.ops.resp.SaveScheduleResponse;
import org.techhouse.ops.resp.SaveSchemaResponse;
import org.techhouse.ops.resp.SaveTriggerResponse;
import org.techhouse.ops.resp.StartTransactionResponse;
import org.techhouse.ops.resp.StopListenResponse;
import org.techhouse.ops.resp.TestTriggerResponse;
import org.techhouse.test.TestUtils;

public class ResponseBytesTest {
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Path RESPONSE_SOURCES = Path.of("src/main/java/org/techhouse/ops/resp");
    private static final Pattern SUBCLASS = Pattern.compile("public class (\\w+) extends (\\w+Response)\\b");
    private static final String DOCUMENT = """
            {"_id":"doc-1","name":"界面 ünïcødé","emoji":"😀 ok","quote":"say \\"hi\\"\\n\\ttab \\u0001",
            "n":42,"neg":-7,"pi":3.141592653589793,"big":12345678901234567890,"tiny":1e-7,"flag":true,
            "off":false,"nothing":null,"nested":{"arr":[1,"two",{"three":3.5}],"empty":[],"none":{}},
            "where":"#geo(10.5,20.25)","vec":"#vector(0.5,1,-2)"}""";

    private static JsonObject document(String id) {
        final var document = eJson.fromJson(DOCUMENT, JsonObject.class);
        document.addProperty("_id", id);
        document.addProperty("lone", "a\uD800b\uDC00c\uDBFF");
        return document;
    }

    private static AnalyzeResult analyzeResult() {
        final var result = new AnalyzeResult();
        result.setStartTime(1);
        result.setEndTime(5);
        result.setDurationMillis(4);
        result.indexesUsed = List.of("name");
        result.locksAcquired = List.of("db|coll");
        result.suggestions = List.of("index 界");
        return result;
    }

    private static List<OperationResponse> fixture() {
        final var doc = document("doc-1");
        final var rows = List.of(doc, document("doc-2"), new JsonObject());
        final var logs = List.of("log 界", "😀");
        return List.of(new OperationResponse(OperationType.LIST_DATABASES, ErrorCode.ERROR_RETRIEVING),
                OperationResponse.ok(OperationType.CREATE_DATABASE, "créé 界"),
                new OperationResponse(OperationType.SAVE, ErrorCode.ERROR_RETRIEVING, "détail \uD800"),
                new AggregateResponse("ok", rows), new AggregateResponse("ok", List.of()),
                new AggregateAnalyzeResponse("ok", rows, analyzeResult()),
                new BulkSaveResponse("ok", List.of("a", "界"), List.of("c")),
                new CallProcedureResponse("ok", doc, logs, false, "run-1"),
                new CallProcedureResponse("failed", ErrorCode.ERROR_RETRIEVING, logs, true, "run-2", List.of("at x")),
                new CancelScriptResponse("ok", true), new CloseConnectionResponse(), new DeleteResponse("ok"),
                new FindByIdResponse("ok", doc), new FindByIdResponse("ok", null),
                new GetDatabaseStatsResponse("ok", doc), new ListCollectionsResponse("ok", List.of("c1", "c2")),
                new ListDatabasesResponse("ok", List.of("db1")), new ListProceduresResponse("ok", rows),
                new ListSchedulesResponse("ok", rows), new ListScriptsResponse("ok", rows),
                new ListTransactionsResponse("ok", rows), new ListTriggerRunsResponse("ok", rows),
                new ListTriggersResponse("ok", rows), new ListUsersResponse("ok", rows),
                new ListenEndedResponse("listen-1", "collection dropped"),
                new ListenResponse("listen-1", rows, "hash", true), new ListenResponse("listen-2", rows, "h", false),
                new PrimaryKeyReindexResponse("ok", List.of("f"),
                        new PkRebuild(List.of(), 2, true, List.of(), List.of())),
                new ReindexResponse("ok", List.of("f", "g")), new ResolveTriggerRunResponse("ok", false),
                new RunScriptResponse("ok", doc, logs, true, "run-3"),
                new RunScriptResponse("failed", ErrorCode.ERROR_RETRIEVING, logs, false, "run-4"),
                new SaveProcedureResponse("ok", 3L), new SaveResponse("ok", "doc-1", true),
                new SaveScheduleResponse("ok", 5L), new SaveSchemaResponse("ok", List.of("warning 界")),
                new SaveTriggerResponse("ok", 4L, "alice"), new StartTransactionResponse("ok", "tx-1"),
                new StopListenResponse(),
                new TestTriggerResponse("ok", "ALLOW", doc, "because", logs, false, List.of("at y")));
    }

    @Test
    public void responsesAreByteIdenticalToTheStringPath() {
        for (final var response : fixture()) {
            final var legacy = eJson.toJson(response).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(legacy, eJson.toJsonBytes(response, response.estimatedJsonChars()),
                    response.getClass().getSimpleName());
            assertArrayEquals(legacy, OutboundQueue.frameOf(response), response.getClass().getSimpleName());
            assertArrayEquals(legacy, eJson.toJsonBytes(response, 0), response.getClass().getSimpleName());
        }
    }

    @Test
    public void test_the_queued_wire_bytes_equal_what_the_buffered_writer_wrote() throws IOException {
        final var legacy = new ByteArrayOutputStream();
        final var writer = new BufferedWriter(new OutputStreamWriter(legacy, StandardCharsets.UTF_8));
        final var queued = new ByteArrayOutputStream();
        final var queue = TestUtils.inlineOutbound(queued);
        for (final var response : fixture()) {
            writer.write(eJson.toJson(response));
            writer.newLine();
            queue.offer(OutboundQueue.frameOf(response));
        }
        writer.flush();
        assertArrayEquals(legacy.toByteArray(), queued.toByteArray());
    }

    @Test
    public void test_the_fixture_covers_every_response_subclass() throws IOException {
        final var declared = new TreeSet<String>();
        try (var sources = Files.list(RESPONSE_SOURCES)) {
            for (final var source : sources.toList()) {
                final var matcher = SUBCLASS.matcher(Files.readString(source));
                if (matcher.find()) {
                    declared.add(matcher.group(1));
                }
            }
        }
        final var covered = new TreeSet<String>();
        fixture().forEach(response -> covered.add(response.getClass().getSimpleName()));
        covered.remove(OperationResponse.class.getSimpleName());
        assertEquals(declared, covered);
    }
}
