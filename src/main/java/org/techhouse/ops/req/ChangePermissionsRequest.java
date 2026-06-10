package org.techhouse.ops.req;

import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.OperationType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ChangePermissionsRequest extends OperationRequest {
    private String username;
    private Boolean admin;
    private Set<GlobalPermissionType> globalPermissions;
    private Map<String, PermissionLevel> databasePermissions;
    private Map<String, PermissionLevel> collectionPermissions;

    public ChangePermissionsRequest() {
        super(OperationType.CHANGE_PERMISSIONS, null, null);
        this.admin = false;
        this.globalPermissions = new HashSet<>();
        this.databasePermissions = new HashMap<>();
        this.collectionPermissions = new HashMap<>();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Boolean getAdmin() {
        return admin != null ? admin : false;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public Set<GlobalPermissionType> getGlobalPermissions() {
        return globalPermissions;
    }

    public void setGlobalPermissions(Set<GlobalPermissionType> globalPermissions) {
        this.globalPermissions = globalPermissions;
    }

    public Map<String, PermissionLevel> getDatabasePermissions() {
        return databasePermissions;
    }

    public void setDatabasePermissions(Map<String, PermissionLevel> databasePermissions) {
        this.databasePermissions = databasePermissions;
    }

    public Map<String, PermissionLevel> getCollectionPermissions() {
        return collectionPermissions;
    }

    public void setCollectionPermissions(Map<String, PermissionLevel> collectionPermissions) {
        this.collectionPermissions = collectionPermissions;
    }
}
