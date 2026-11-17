package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.ListenEndedException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.UnavailableException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.values.Vector;
import io.github.christiancg.lwnrdb.testsupport.ProtocolFixtures;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.node.ObjectNode;

public class ResponseFixturesTest {
    private static final String ERROR_PREFIX = "error:";

    @TestFactory
    public Stream<DynamicTest> test_every_response_fixture_decodes_to_its_expectation() {
        return ProtocolFixtures.responses().stream().map(fixture -> DynamicTest.dynamicTest(fixture.name(), () -> {
            final var frame = (ObjectNode) fixture.body().get("frame");
            final var expect = fixture.body().get("expect").asString();
            switch (expect) {
                case "ok" -> {
                    assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(frame, false));
                    assertDoesNotThrow(() -> ResponseDecoder.check(frame));
                }
                case "push" -> {
                    assertEquals(FrameRouter.Route.PUSH, FrameRouter.route(frame, false));
                    assertFalse(FrameRouter.isListenEnd(frame));
                }
                case "listenEnded" -> {
                    assertEquals(FrameRouter.Route.PUSH, FrameRouter.route(frame, false));
                    assertInstanceOf(ListenEndedException.class, ResponseDecoder.raise(frame));
                }
                case "serverClosed" -> assertEquals(FrameRouter.Route.SERVER_CLOSE, FrameRouter.route(frame, false));
                default -> assertError(frame, expect.substring(ERROR_PREFIX.length()));
            }
        }));
    }

    private static void assertError(ObjectNode frame, String code) {
        assertEquals(FrameRouter.Route.RESPONSE, FrameRouter.route(frame, false));
        final var error = assertThrows(LwnrdbException.class, () -> ResponseDecoder.check(frame));
        assertEquals(code, error.rawCode().orElseThrow());
        final var known = ErrorCode.fromCode(code);
        if (known.map(ErrorCode::isRetryable).orElse(false)) {
            assertInstanceOf(UnavailableException.class, error);
        }
        if ("500-33".equals(code) || "500-34".equals(code)) {
            assertInstanceOf(UnknownOutcomeException.class, error);
        }
        assertTrue(error.getMessage().contains(code));
    }

    @Test
    public void test_custom_values_in_a_reply_decode_through_document_getters() {
        final var frame = ProtocolFixtures.responses().stream().filter(c -> c.name().equals("ok.find_by_id"))
                .findFirst().orElseThrow().body().get("frame");
        final var document = ResponseDecoder.document((ObjectNode) frame, "object").orElseThrow();
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), document.getDateTime("openedAt").orElseThrow());
        assertEquals(Vector.of(0.12, 4.4E-4, -0.0, 1.0E21), document.getVector("embedding").orElseThrow());
        assertEquals(40.71, document.getGeo("location").orElseThrow().lat());
        assertEquals("09:30", document.getTime("opensAt").orElseThrow().toString());
    }
}
