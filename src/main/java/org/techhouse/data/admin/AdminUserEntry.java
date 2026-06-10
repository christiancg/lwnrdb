package org.techhouse.data.admin;

import org.techhouse.config.Globals;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AdminUserEntry extends DbEntry {
    private static final String PASSWORD_HASH_FIELD = "passwordHash";
    private static final String ADMIN_FIELD = "admin";
    private static final String GLOBAL_PERMISSIONS_FIELD = "globalPermissions";
    private static final String DATABASE_PERMISSIONS_FIELD = "databasePermissions";
    private static final String COLLECTION_PERMISSIONS_FIELD = "collectionPermissions";

    private String passwordHash;
    private boolean admin;
    private Set<GlobalPermissionType> globalPermissions;
    private Map<String, PermissionLevel> databasePermissions;
    private Map<String, PermissionLevel> collectionPermissions;

    private AdminUserEntry() {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_USERS_COLLECTION_NAME);
    }

    public AdminUserEntry(String username, String passwordHash, boolean admin,
                         Set<GlobalPermissionType> globalPermissions,
                         Map<String, PermissionLevel> databasePermissions,
                         Map<String, PermissionLevel> collectionPermissions) {
        super.setDatabaseName(Globals.ADMIN_DB_NAME);
        super.setCollectionName(Globals.ADMIN_USERS_COLLECTION_NAME);
        this.set_id(username);
        this.passwordHash = passwordHash;
        this.admin = admin;
        this.globalPermissions = globalPermissions;
        this.databasePermissions = databasePermissions;
        this.collectionPermissions = collectionPermissions;
        rebuildData();
    }

    public static AdminUserEntry fromJsonObject(JsonObject object) {
        final var result = new AdminUserEntry();
        result.setData(object);
        final var username = object.get(Globals.PK_FIELD).asJsonString().getValue();
        result.set_id(username);
        result.passwordHash = object.get(PASSWORD_HASH_FIELD).asJsonString().getValue();
        result.admin = object.get(ADMIN_FIELD).asJsonBoolean().getValue();

        final var globalPermsArray = object.get(GLOBAL_PERMISSIONS_FIELD).asJsonArray();
        result.globalPermissions = globalPermsArray.asList().stream()
                .map(el -> GlobalPermissionType.valueOf(el.asJsonString().getValue()))
                .collect(Collectors.toSet());

        final var dbPermsObj = object.get(DATABASE_PERMISSIONS_FIELD).asJsonObject();
        result.databasePermissions = new HashMap<>();
        for (final var entry : dbPermsObj.entrySet()) {
            final var level = PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
            result.databasePermissions.put(entry.getKey(), level);
        }

        final var collPermsObj = object.get(COLLECTION_PERMISSIONS_FIELD).asJsonObject();
        result.collectionPermissions = new HashMap<>();
        for (final var entry : collPermsObj.entrySet()) {
            final var level = PermissionLevel.valueOf(entry.getValue().asJsonString().getValue());
            result.collectionPermissions.put(entry.getKey(), level);
        }

        result.setDatabaseName(Globals.ADMIN_DB_NAME);
        result.setCollectionName(Globals.ADMIN_USERS_COLLECTION_NAME);
        return result;
    }

    private void rebuildData() {
        final var json = new JsonObject();
        json.add(Globals.PK_FIELD, new JsonString(this.get_id()));
        json.add(PASSWORD_HASH_FIELD, new JsonString(passwordHash));
        json.add(ADMIN_FIELD, new JsonBoolean(admin));

        final var globalPermsArray = new JsonArray();
        globalPermissions.forEach(perm -> globalPermsArray.add(new JsonString(perm.name())));
        json.add(GLOBAL_PERMISSIONS_FIELD, globalPermsArray);

        final var dbPermsObj = new JsonObject();
        databasePermissions.forEach((db, level) -> dbPermsObj.add(db, new JsonString(level.name())));
        json.add(DATABASE_PERMISSIONS_FIELD, dbPermsObj);

        final var collPermsObj = new JsonObject();
        collectionPermissions.forEach((coll, level) -> collPermsObj.add(coll, new JsonString(level.name())));
        json.add(COLLECTION_PERMISSIONS_FIELD, collPermsObj);

        this.setData(json);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
        rebuildData();
    }

    public Set<GlobalPermissionType> getGlobalPermissions() {
        return globalPermissions;
    }

    public void setGlobalPermissions(Set<GlobalPermissionType> globalPermissions) {
        this.globalPermissions = globalPermissions;
        rebuildData();
    }

    public Map<String, PermissionLevel> getDatabasePermissions() {
        return databasePermissions;
    }

    public Map<String, PermissionLevel> getCollectionPermissions() {
        return collectionPermissions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdminUserEntry that)) return false;
        if (!super.equals(o)) return false;
        return admin == that.admin && Objects.equals(passwordHash, that.passwordHash) &&
                Objects.equals(globalPermissions, that.globalPermissions) &&
                Objects.equals(databasePermissions, that.databasePermissions) &&
                Objects.equals(collectionPermissions, that.collectionPermissions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), passwordHash, admin, globalPermissions, databasePermissions, collectionPermissions);
    }

    @Override
    public String toString() {
        return "AdminUserEntry(super=" + super.toString() + ", passwordHash=***, admin=" + admin +
                ", globalPermissions=" + globalPermissions + ", databasePermissions=" + databasePermissions +
                ", collectionPermissions=" + collectionPermissions + ")";
    }
}
