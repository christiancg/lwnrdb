package org.techhouse.client;

public final class ReplyHeader {
    private static final int MAX_CAPTURED = 64;
    private static final String OK = "OK";

    private final char[] captured = new char[MAX_CAPTURED];
    private int capturedLength;
    private int depth;
    private boolean started;
    private boolean notAnObject;
    private boolean inString;
    private boolean escaped;
    private boolean expectingKey;
    private boolean capturing;
    private boolean capturingKey;
    private String key;
    private String type;
    private String status;
    private String errorCode;

    public static ReplyHeader of(String raw) {
        final var header = new ReplyHeader();
        for (var i = 0; i < raw.length(); i++) {
            header.accept(raw.charAt(i));
        }
        return header;
    }

    public void accept(byte[] bytes, int from, int to) {
        for (var i = from; i < to; i++) {
            accept(bytes[i] & 0xFF);
        }
    }

    private void accept(int unit) {
        if (notAnObject) {
            return;
        }
        if (inString) {
            acceptInString(unit);
            return;
        }
        if (!started) {
            if (unit == '{') {
                started = true;
                depth = 1;
                expectingKey = true;
            } else if (!Character.isWhitespace(unit)) {
                notAnObject = true;
            }
            return;
        }
        switch (unit) {
            case '"' -> startString();
            case '{', '[' -> depth++;
            case '}', ']' -> depth--;
            case ':' -> expectingKey &= depth != 1;
            case ',' -> expectingKey |= depth == 1;
            default -> {
            }
        }
    }

    private void acceptInString(int unit) {
        if (escaped) {
            escaped = false;
            capture(unit);
        } else if (unit == '\\') {
            escaped = true;
        } else if (unit == '"') {
            inString = false;
            endString();
        } else {
            capture(unit);
        }
    }

    private void startString() {
        inString = true;
        capturingKey = depth == 1 && expectingKey;
        capturing = capturingKey || depth == 1 && isWanted(key);
        capturedLength = 0;
    }

    private void capture(int unit) {
        if (capturing && capturedLength < MAX_CAPTURED) {
            captured[capturedLength++] = (char) unit;
        }
    }

    private void endString() {
        if (!capturing) {
            return;
        }
        final var text = new String(captured, 0, capturedLength);
        if (capturingKey) {
            key = text;
            return;
        }
        switch (key) {
            case Requests.TYPE -> type = text;
            case Connection.STATUS -> status = text;
            case Connection.ERROR_CODE -> errorCode = text;
            default -> {
            }
        }
    }

    private static boolean isWanted(String key) {
        return Requests.TYPE.equals(key) || Connection.STATUS.equals(key) || Connection.ERROR_CODE.equals(key);
    }

    public String type() {
        return type;
    }

    public String status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean isOk() {
        return OK.equals(status);
    }
}
