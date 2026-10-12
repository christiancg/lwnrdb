package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenCapacityTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final Configuration configuration = Configuration.getInstance();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        listenManager.unregisterAllForClient(first);
        listenManager.unregisterAllForClient(second);
        TestUtils.setPrivateField(configuration, "maxListensPerConnection",
                ConfigKey.MAX_LISTENS_PER_CONNECTION.defaultValue());
        TestUtils.setPrivateField(configuration, "maxListens", ConfigKey.MAX_LISTENS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private OperationResponse listen(UUID clientId) {
        final var request = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        return processor.processMessage(request, clientId);
    }

    @Test
    public void test_a_connection_past_its_listen_allowance_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "maxListensPerConnection", 2);
        assertInstanceOf(ListenResponse.class, listen(first));
        assertInstanceOf(ListenResponse.class, listen(first));
        assertEquals(ErrorCode.TOO_MANY_LISTENS.getCode(), listen(first).getErrorCode());
        assertInstanceOf(ListenResponse.class, listen(second));
    }

    @Test
    public void test_the_server_wide_allowance_spans_connections() throws Exception {
        TestUtils.setPrivateField(configuration, "maxListens", 2);
        listen(first);
        listen(second);
        assertFalse(listenManager.hasCapacityFor(UUID.randomUUID()));
        assertEquals(ErrorCode.TOO_MANY_LISTENS.getCode(), listen(second).getErrorCode());
    }

    @Test
    public void test_unregistering_frees_capacity_and_zero_means_unlimited() throws Exception {
        TestUtils.setPrivateField(configuration, "maxListensPerConnection", 1);
        listen(first);
        assertFalse(listenManager.hasCapacityFor(first));
        listenManager.unregisterAllForClient(first);
        assertTrue(listenManager.hasCapacityFor(first));
        TestUtils.setPrivateField(configuration, "maxListensPerConnection", 0);
        TestUtils.setPrivateField(configuration, "maxListens", 0);
        for (var i = 0; i < 5; i++) {
            assertInstanceOf(ListenResponse.class, listen(first));
        }
        assertTrue(listenManager.hasCapacityFor(first));
    }
}
