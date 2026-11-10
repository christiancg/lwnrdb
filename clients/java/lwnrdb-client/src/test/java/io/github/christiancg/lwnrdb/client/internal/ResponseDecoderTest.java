package io.github.christiancg.lwnrdb.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ConflictException;
import io.github.christiancg.lwnrdb.client.error.ForbiddenException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.NotFoundException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import io.github.christiancg.lwnrdb.client.protocol.Status;
import io.github.christiancg.lwnrdb.client.testing.FakeServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

public class ResponseDecoderTest {
    private static ObjectNode frame(String status, String code) {
        final var frame = FakeServer.MAPPER.createObjectNode().put("type", "SAVE").put("status", status);
        if (code != null) {
            frame.put("errorCode", code);
        }
        return frame;
    }

    @Test
    public void test_status_alone_picks_the_exception_when_no_code_is_sent() {
        assertInstanceOf(AuthenticationException.class, ResponseDecoder.raise(frame("UNAUTHENTICATED", null)));
        assertInstanceOf(ForbiddenException.class, ResponseDecoder.raise(frame("FORBIDDEN", null)));
        assertInstanceOf(NotFoundException.class, ResponseDecoder.raise(frame("NOT_FOUND", null)));
        assertEquals(LwnrdbException.class, ResponseDecoder.raise(frame("ERROR", null)).getClass());
    }

    @Test
    public void test_an_unknown_code_keeps_its_raw_code() {
        final var error = ResponseDecoder.raise(frame("ERROR", "599-1"));
        assertEquals("599-1", error.rawCode().orElseThrow());
        assertTrue(error.errorCode().isEmpty());
        assertEquals(Status.ERROR, error.status().orElseThrow());
    }

    @Test
    public void test_a_known_code_without_message_uses_the_catalogue_message() {
        final var error = ResponseDecoder.raise(frame("ERROR", "409-3"));
        assertInstanceOf(ConflictException.class, error);
        assertTrue(error.getMessage()
                .startsWith(ErrorCode.fromCode("409-3").orElseThrow().defaultMessage().orElseThrow()));
        assertTrue(error.hasCode(ErrorCode.TRANSACTION_ALREADY_ACTIVE));
    }

    @Test
    public void test_a_short_or_absent_status_is_tolerated() {
        assertEquals(LwnrdbException.class, ResponseDecoder.raise(frame("ERROR", "4")).getClass());
        final var noStatus = FakeServer.MAPPER.createObjectNode().put("type", "SAVE");
        assertEquals(noStatus, ResponseDecoder.check(noStatus));
        assertEquals(Status.ERROR, ResponseDecoder.raise(frame("WEIRD", null)).status().orElseThrow());
        assertEquals(LwnrdbException.class, ResponseDecoder.raise(noStatus).getClass());
    }

    @Test
    public void test_no_operation_and_ok_never_raise() {
        assertEquals("OK", ResponseDecoder.check(frame("OK", null)).get("status").asString());
        assertEquals("NO_OPERATION", ResponseDecoder.check(frame("NO_OPERATION", null)).get("status").asString());
    }

    @Test
    public void test_documents_skip_non_objects_and_strings_render_scalars() {
        final var frame = FakeServer.MAPPER.createObjectNode();
        frame.putArray("results").add(1).addObject().put("_id", "a");
        frame.putArray("names").add("x").add(2);
        assertEquals(1, ResponseDecoder.documents(frame, "results").size());
        assertTrue(ResponseDecoder.documents(frame, "missing").isEmpty());
        assertEquals(java.util.List.of("x", "2"), ResponseDecoder.strings(frame.get("names")));
        assertTrue(ResponseDecoder.strings(null).isEmpty());
        assertTrue(ResponseDecoder.document(frame, "names").isEmpty());
    }
}
