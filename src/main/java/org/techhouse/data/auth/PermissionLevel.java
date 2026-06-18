package org.techhouse.data.auth;

public enum PermissionLevel {
    READ, READ_WRITE;

    public boolean covers(PermissionLevel required) {
        return this == READ_WRITE || required == READ;
    }
}
