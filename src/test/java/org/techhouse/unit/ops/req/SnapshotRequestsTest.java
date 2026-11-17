package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.ReadSnapshotRequest;
import org.techhouse.ops.req.ReleaseSnapshotRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.StartSnapshotRequest;
import org.techhouse.ops.req.validations.RequestValidator;

public class SnapshotRequestsTest {
    private static final String SNAPSHOT_ID = "6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10";

    private static boolean valid(String json) {
        return RequestValidator.validate(RequestParser.parseRequest(json)).isValid();
    }

    private static String readOf(String extra) {
        return "{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionName\":\"orders\",\"snapshotId\":\""
                + SNAPSHOT_ID + "\"" + extra + "}";
    }

    @Test
    public void test_the_three_requests_parse_into_their_classes() {
        final var start = assertInstanceOf(StartSnapshotRequest.class, RequestParser.parseRequest(
                "{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionNames\":[\"orders\"]}"));
        assertEquals(OperationType.START_SNAPSHOT, start.getType());
        assertEquals(List.of("orders"), start.getCollectionNames());

        final var read = assertInstanceOf(ReadSnapshotRequest.class,
                RequestParser.parseRequest(readOf(",\"afterId\":\"o-9\",\"limit\":50")));
        assertEquals("orders", read.getCollectionName());
        assertEquals(SNAPSHOT_ID, read.getSnapshotId());
        assertEquals("o-9", read.getAfterId());
        assertEquals(50, read.getLimit());

        final var release = assertInstanceOf(ReleaseSnapshotRequest.class, RequestParser.parseRequest(
                "{\"type\":\"RELEASE_SNAPSHOT\",\"databaseName\":\"shop\",\"snapshotId\":\"" + SNAPSHOT_ID + "\"}"));
        assertEquals(SNAPSHOT_ID, release.getSnapshotId());
    }

    @Test
    public void test_start_validation() {
        assertTrue(valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\"}"));
        assertTrue(
                valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionNames\":[\"a_1\",\"b-2\"]}"));
        assertFalse(valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"admin\"}"));
        assertFalse(valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionNames\":[]}"));
        assertFalse(valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionNames\":[\"a/b\"]}"));
        assertFalse(
                valid("{\"type\":\"START_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionNames\":[\"abc\",\"abc\"]}"));
    }

    @Test
    public void test_read_validation() {
        assertTrue(valid(readOf(",\"limit\":1")));
        assertTrue(valid(readOf(",\"afterId\":\"o-9\",\"limit\":100000")));
        assertFalse(valid(readOf(",\"limit\":0")));
        assertFalse(valid(readOf(",\"limit\":100001")));
        assertFalse(valid(readOf(",\"afterId\":\"no spaces\",\"limit\":5")));
        assertFalse(valid("{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionName\":\"orders\","
                + "\"snapshotId\":\"not-a-uuid\",\"limit\":5}"));
        assertFalse(valid(
                "{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"shop\",\"collectionName\":\"orders\",\"limit\":5}"));
        assertFalse(valid("{\"type\":\"READ_SNAPSHOT\",\"databaseName\":\"admin\",\"collectionName\":\"orders\","
                + "\"snapshotId\":\"" + SNAPSHOT_ID + "\",\"limit\":5}"));
    }

    @Test
    public void test_release_validation() {
        assertTrue(valid(
                "{\"type\":\"RELEASE_SNAPSHOT\",\"databaseName\":\"shop\",\"snapshotId\":\"" + SNAPSHOT_ID + "\"}"));
        assertFalse(valid("{\"type\":\"RELEASE_SNAPSHOT\",\"databaseName\":\"shop\"}"));
        assertFalse(
                valid("{\"type\":\"RELEASE_SNAPSHOT\",\"databaseName\":\"x\",\"snapshotId\":\"" + SNAPSHOT_ID + "\"}"));
        assertNull(new ReleaseSnapshotRequest("shop").getSnapshotId());
    }
}
