package org.techhouse.simplejs.host;

import org.techhouse.ejson.elements.JsonBaseElement;

public final class ScriptResult {
    private final JsonBaseElement value;
    private final String errorName;
    private final String errorMessage;
    private final boolean error;

    private ScriptResult(JsonBaseElement value, String errorName, String errorMessage, boolean error) {
        this.value = value;
        this.errorName = errorName;
        this.errorMessage = errorMessage;
        this.error = error;
    }

    public static ScriptResult value(JsonBaseElement value) {
        return new ScriptResult(value, null, null, false);
    }

    public static ScriptResult error(String name, String message) {
        return new ScriptResult(null, name, message, true);
    }

    public boolean isError() {
        return error;
    }

    public JsonBaseElement getValue() {
        return value;
    }

    public String getErrorName() {
        return errorName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
