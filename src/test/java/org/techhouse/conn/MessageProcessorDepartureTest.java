package org.techhouse.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.test.TestUtils;

public class MessageProcessorDepartureTest {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.resetClients();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.resetClients();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void aFencedClientReleasesItsSlotAndListensBeforeItsCommitIsRedriven() throws Exception {
        final var processor = new MessageProcessor(
                TestUtils.clientSocket(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        final var clientId = TestUtils.getPrivateField(processor, "clientId", UUID.class);
        final var slots = TestUtils.getPrivateField(clientTracker, "socketClients", AtomicInteger.class);
        final var listens = Mockito.mock(ListenManager.class);
        final var reaper = Mockito.mock(IdleTransactionReaper.class);
        final var slotsDuringRedrive = new int[]{-1};
        Mockito.doAnswer(_ -> {
            slotsDuringRedrive[0] = slots.get();
            return null;
        }).when(reaper).redriveUntilFinished();
        TestUtils.setPrivateField(processor, "listenManager", listens);
        TestUtils.setPrivateField(processor, "reaper", reaper);
        final var slotsBefore = slots.get();

        processor.run();

        final var order = Mockito.inOrder(listens, reaper);
        order.verify(listens).unregisterAllForClient(clientId);
        order.verify(reaper).redriveUntilFinished();
        assertEquals(slotsBefore - 1, slotsDuringRedrive[0], "a redriven commit must not hold a connection slot");
        assertEquals(slotsBefore - 1, slots.get(), "the slot is freed once");
        assertFalse(clientTracker.clientIdsSnapshot().contains(clientId));
    }
}
