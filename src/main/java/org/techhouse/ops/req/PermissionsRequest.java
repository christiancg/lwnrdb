package org.techhouse.ops.req;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.OperationType;

public abstract class PermissionsRequest extends OperationRequest {
    protected String username;
    protected Boolean admin;
    protected JsonArray globalPermissions;
    protected JsonObject databasePermissions;
    protected JsonObject collectionPermissions;
    protected JsonObject scriptPermissions;

    protected PermissionsRequest(OperationType type, String databaseName, String collectionName) {
        super(type, databaseName, collectionName);
    }

    public Boolean getAdmin() {
        return admin != null && admin;
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

    public Map<String, PermissionLevel> getDatabasePermissions() {
        if (databasePermissions == null)
            return new HashMap<>();
        final var result = new HashMap<String, PermissionLevel>();
        for (var entry : databasePermissions.entrySet()) {
            result.put(entry.getKey(), PermissionLevel.valueOf(entry.getValue().asJsonString().getValue()));
        }
        return result;
    }

    public Set<GlobalPermissionType> getGlobalPermissions() {
        if (globalPermissions == null)
            return new HashSet<>();
        return globalPermissions.asList().stream().map(e -> GlobalPermissionType.valueOf(e.asJsonString().getValue()))
                .collect(Collectors.toSet());
    }

    public JsonObject getRawCollectionPermissions() {
        return collectionPermissions;
    }

    public JsonObject getRawDatabasePermissions() {
        return databasePermissions;
    }

    public JsonObject getRawScriptPermissions() {
        return scriptPermissions;
    }

    public Map<String, ScriptPermissionLevel> getScriptPermissions() {
        if (scriptPermissions == null)
            return new HashMap<>();
        final var result = new HashMap<String, ScriptPermissionLevel>();
        for (var entry : scriptPermissions.entrySet()) {
            result.put(entry.getKey(), ScriptPermissionLevel.fromJson(entry.getValue()));
        }
        return result;
    }

    public String getUsername() {
        return username;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public void setCollectionPermissions(Map<String, PermissionLevel> perms) {
        this.collectionPermissions = new JsonObject();
        perms.forEach((k, v) -> this.collectionPermissions.add(k, new JsonString(v.name())));
    }

    public void setDatabasePermissions(Map<String, PermissionLevel> perms) {
        this.databasePermissions = new JsonObject();
        perms.forEach((k, v) -> this.databasePermissions.add(k, new JsonString(v.name())));
    }

    public void setGlobalPermissions(Set<GlobalPermissionType> perms) {
        this.globalPermissions = new JsonArray();
        perms.forEach(p -> this.globalPermissions.add(new JsonString(p.name())));
    }

    public void setScriptPermissions(Map<String, ScriptPermissionLevel> perms) {
        this.scriptPermissions = new JsonObject();
        perms.forEach((k, v) -> this.scriptPermissions.add(k, new JsonString(v.name())));
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
