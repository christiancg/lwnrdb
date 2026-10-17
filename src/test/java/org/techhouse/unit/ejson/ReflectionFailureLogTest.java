package org.techhouse.unit.ejson;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ops.req.OperationRequest;

public class ReflectionFailureLogTest {
    @Test
    public void test_an_unreadable_request_type_yields_no_object_however_often_it_is_sent() {
        final var ejson = new EJson();
        for (var i = 0; i < 100; i++) {
            assertNull(ejson.fromJson("{\"type\":\"BOGUS\"}", OperationRequest.class));
        }
    }
}
