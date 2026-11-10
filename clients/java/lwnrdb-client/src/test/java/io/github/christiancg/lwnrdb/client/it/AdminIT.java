package io.github.christiancg.lwnrdb.client.it;

import static io.github.christiancg.lwnrdb.client.query.Filters.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.admin.DataPermission;
import io.github.christiancg.lwnrdb.client.admin.Permissions;
import io.github.christiancg.lwnrdb.client.error.AuthenticationException;
import io.github.christiancg.lwnrdb.client.error.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.Test;

public class AdminIT extends ServerIT {
    private static final String BOB_PASSWORD = "Bob-Secret-1234";

    private LwnrdbClient asBob() {
        return LwnrdbClient.connect(ItSettings.of(server).credentials("bob", BOB_PASSWORD).build());
    }

    @Test
    public void test_users_permissions_owners_and_passwords() {
        client.database("admin_it").create();
        client.database("admin_it").collection("orders").create();
        client.admin().users().create("bob", BOB_PASSWORD,
                Permissions.builder().database("admin_it", DataPermission.READ).build());
        try (var bob = asBob()) {
            final var orders = bob.database("admin_it").collection("orders");
            assertTrue(orders.query().toList().isEmpty());
            final var refused = assertThrows(ForbiddenException.class, () -> orders.save(Document.of("_id", "x")));
            assertTrue(refused.rawCode().orElseThrow().startsWith("403"));
            assertThrows(ForbiddenException.class, () -> bob.admin().users().list());
            bob.changePassword(BOB_PASSWORD, "Bob-Secret-5678");
        }
        assertThrows(AuthenticationException.class, () -> asBob().close());
        client.admin().users().changePermissions("bob",
                Permissions.builder().database("admin_it", DataPermission.READ_WRITE).build());
        client.admin().users().setPassword("bob", BOB_PASSWORD);
        try (var bob = asBob()) {
            bob.database("admin_it").collection("orders").save(Document.of("_id", "y"));
        }
        client.database("admin_it").setOwners(List.of("bob"));
        final var listed = client.admin().users().list(field("_id").eq("bob"));
        assertEquals(1, listed.size());
        assertEquals(List.of("admin_it"), listed.get(0).ownedDatabases());
        assertTrue(client.admin().users().list().size() >= 2);
        client.admin().users().delete("bob");
        assertTrue(client.admin().users().list(field("_id").eq("bob")).isEmpty());
    }

    @Test
    public void test_server_info_stats_and_transactions() {
        final var info = client.serverInfo();
        assertEquals("READY", info.state().orElseThrow());
        assertTrue(info.version().isPresent());
        assertTrue(info.raw().has("node"));
        client.database("stats_it").create();
        assertFalse(client.databaseStats().fieldNames().isEmpty());
        assertNotNull(client.database("stats_it").stats());
        assertTrue(client.admin().transactions().list().isEmpty());
    }
}
