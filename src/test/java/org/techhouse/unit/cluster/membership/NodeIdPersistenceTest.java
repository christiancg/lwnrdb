package org.techhouse.unit.cluster.membership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class NodeIdPersistenceTest {
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        Files.deleteIfExists(nodeIdPath());
        TestUtils.standardTearDown();
    }

    private static Path nodeIdPath() {
        return Paths.get(Configuration.getInstance().getFilePath(), Globals.CLUSTER_FOLDER,
                Globals.CLUSTER_NODE_ID_FILE);
    }

    private static void writeNodeIdFile(String content) throws Exception {
        Files.createDirectories(Objects.requireNonNull(nodeIdPath().getParent()));
        Files.writeString(nodeIdPath(), content, StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_generated_id_is_persisted_and_reused() {
        final var first = membershipService.resolveNodeId();
        final var second = membershipService.resolveNodeId();

        assertEquals(first, second, "the node id must be stable across calls, or ownership reshuffles");
        assertEquals(first, UUID.fromString(first).toString(), "a generated id must be a well formed uuid");
    }

    @Test
    public void test_a_truncated_id_is_refused_instead_of_silently_adopted() throws Exception {
        writeNodeIdFile("4f3a21b8-9c0d-4e1f-a2b3");

        assertThrows(IllegalStateException.class, membershipService::resolveNodeId,
                "a truncated uuid is non-blank, so it used to be returned verbatim and kept forever");
    }

    @Test
    public void test_a_blank_id_file_is_refused() throws Exception {
        writeNodeIdFile("   ");

        assertThrows(IllegalStateException.class, membershipService::resolveNodeId,
                "an empty file is the shape a crash mid-write leaves");
    }

    @Test
    public void test_a_well_formed_stored_id_is_returned_unchanged() throws Exception {
        final var stored = UUID.randomUUID().toString();
        writeNodeIdFile(stored);

        assertEquals(stored, membershipService.resolveNodeId(), "a valid persisted id must survive a restart");
    }

    @Test
    public void test_the_id_is_written_atomically() {
        membershipService.resolveNodeId();

        final var tmp = nodeIdPath().resolveSibling(nodeIdPath().getFileName() + ".tmp");
        assertFalse(Files.exists(tmp), "the atomic write must move its temp file into place, not leave it");
        assertTrue(Files.exists(nodeIdPath()), "the id must actually be persisted");
    }
}
