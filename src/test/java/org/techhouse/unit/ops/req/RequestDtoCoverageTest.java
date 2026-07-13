package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;

public class RequestDtoCoverageTest {

    @Test
    public void test_change_permissions_getters_round_trip() {
        final var request = new ChangePermissionsRequest();
        request.setUsername("bob");
        request.setAdmin(true);
        request.setGlobalPermissions(Set.of(GlobalPermissionType.CREATE_DATABASE));
        request.setDatabasePermissions(Map.of("db1", PermissionLevel.READ_WRITE));
        request.setCollectionPermissions(Map.of("db1|c1", PermissionLevel.READ));

        assertEquals("bob", request.getUsername());
        assertTrue(request.getAdmin());
        assertEquals(Set.of(GlobalPermissionType.CREATE_DATABASE), request.getGlobalPermissions());
        assertEquals(PermissionLevel.READ_WRITE, request.getDatabasePermissions().get("db1"));
        assertEquals(PermissionLevel.READ, request.getCollectionPermissions().get("db1|c1"));
    }

    @Test
    public void test_create_user_getters_round_trip() {
        final var request = new CreateUserRequest();
        request.setUsername("carol");
        request.setPassword("secret");
        request.setAdmin(false);
        request.setGlobalPermissions(Set.of(GlobalPermissionType.DROP_DATABASE));
        request.setDatabasePermissions(Map.of("db2", PermissionLevel.READ));
        request.setCollectionPermissions(Map.of("db2|c2", PermissionLevel.READ_WRITE));

        assertEquals("carol", request.getUsername());
        assertEquals("secret", request.getPassword());
        assertEquals(false, request.getAdmin());
        assertEquals(Set.of(GlobalPermissionType.DROP_DATABASE), request.getGlobalPermissions());
        assertEquals(PermissionLevel.READ, request.getDatabasePermissions().get("db2"));
        assertEquals(PermissionLevel.READ_WRITE, request.getCollectionPermissions().get("db2|c2"));
    }
}
