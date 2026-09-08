package org.techhouse.simplejs.builtins;

import java.util.List;

public final class Base64Tables {
    public static final String BASE64_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    public static final String BASE64URL_DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    public static final String WHITESPACE = " \t\n\f\r";

    public static final List<String> ALPHABETS = List.of("base64", "base64url");

    public static final List<String> CHUNK_HANDLINGS = List.of("loose", "strict", "stop-before-partial");

    private Base64Tables() {
    }
}
