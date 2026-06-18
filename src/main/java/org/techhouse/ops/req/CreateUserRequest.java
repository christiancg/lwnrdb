package org.techhouse.ops.req;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.OperationType;

public class CreateUserRequest extends OperationRequest {
    private String username;
    private String password;
    private Boolean admin;
    private JsonArray globalPermissions;
    private JsonObject databasePermissions;
    private JsonObject collectionPermissions;

    public CreateUserRequest() {
        super(OperationType.CREATE_USER, null, null);
        this.admin = false;
        this.globalPermissions = new JsonArray();
        this.databasePermissions = new JsonObject();
        this.collectionPermissions = new JsonObject();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getAdmin() {
        return admin != null ? admin : false;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public Set<GlobalPermissionType> getGlobalPermissions() {
        if (globalPermissions == null)
            return new HashSet<>();
        return globalPermissions.asList().stream().map(e -> GlobalPermissionType.valueOf(e.asJsonString().getValue()))
                .collect(Collectors.toSet());
    }

    public Map<String, PermissionLevel> getDatabasePermissions() {
        if (databasePermissions == null)
            return new HashMap<>();
        final var result = new HashMap<String, PermissionLevel>();
        for (var entry : databasePermissions.entrySet()) {
            result.put(entry.getKey(), PermissionLevel.valueOf(entry.getValue().asJsonString().getValue()));
        }
        return result;
    }

    public Map<String, PermissionLevel> getCollectionPermissions() {
        if (collectionPermissions == null)
            return new HashMap<>();
        final var result = new HashMap<String, PermissionLevel>();
        for (var entry : collectionPermissions.entrySet()) {
            result.put(entry.getKey(), PermissionLevel.valueOf(entry.getValue().asJsonString().getValue()));
        }
        return result;
    }

    public void setGlobalPermissions(Set<GlobalPermissionType> perms) {
        this.globalPermissions = new JsonArray();
        perms.forEach(p -> this.globalPermissions.add(new JsonString(p.name())));
    }

    public void setDatabasePermissions(Map<String, PermissionLevel> perms) {
        this.databasePermissions = new JsonObject();
        perms.forEach((k, v) -> this.databasePermissions.add(k, new JsonString(v.name())));
    }

    public void setCollectionPermissions(Map<String, PermissionLevel> perms) {
        this.collectionPermissions = new JsonObject();
        perms.forEach((k, v) -> this.collectionPermissions.add(k, new JsonString(v.name())));
    }

    public JsonObject getRawDatabasePermissions() {
        return databasePermissions;
    }

    public JsonObject getRawCollectionPermissions() {
        return collectionPermissions;
    }
}
