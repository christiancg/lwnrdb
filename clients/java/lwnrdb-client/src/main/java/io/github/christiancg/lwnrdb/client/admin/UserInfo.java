package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.List;
import java.util.Map;

public record UserInfo(String username, boolean admin, List<String> globalPermissions,
        Map<String, Object> databasePermissions, Map<String, Object> collectionPermissions,
        Map<String, Object> scriptPermissions, List<String> ownedDatabases, Document raw) {
}
