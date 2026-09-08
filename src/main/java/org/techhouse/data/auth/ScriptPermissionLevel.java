package org.techhouse.data.auth;

import org.techhouse.ejson.elements.JsonBaseElement;

public enum ScriptPermissionLevel {
    NONE, RUN, MANAGE;

    public boolean covers(ScriptPermissionLevel required) {
        return ordinal() >= required.ordinal();
    }

    public static ScriptPermissionLevel parseOrNone(String name) {
        if (name == null) {
            return NONE;
        }
        for (final var level : values()) {
            if (level.name().equals(name)) {
                return level;
            }
        }
        return NONE;
    }

    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        for (final var level : values()) {
            if (level.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static ScriptPermissionLevel fromJson(JsonBaseElement element) {
        if (element == null || element.isJsonNull()) {
            return NONE;
        }
        if (element.isJsonBoolean()) {
            return element.asJsonBoolean().getValue() ? RUN : NONE;
        }
        return element.isJsonString() ? parseOrNone(element.asJsonString().getValue()) : NONE;
    }
}
