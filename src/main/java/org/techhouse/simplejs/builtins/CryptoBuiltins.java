package org.techhouse.simplejs.builtins;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

/**
 * The {@code crypto} namespace: enough of the WHATWG surface for a stored procedure to mint ids and
 * hash content, installed the same way {@code Math}/{@code JSON}/{@code Reflect} are.
 *
 * <p>Two deliberate divergences from WHATWG: {@code hash} is synchronous and Node-shaped rather than
 * the promise-returning {@code crypto.subtle.digest} (the digest is CPU-bound and in-process, and a
 * stored procedure computing a content hash wants a value, not a microtask), and an oversized
 * {@code getRandomValues} request is a {@code RangeError} rather than a {@code QuotaExceededError}
 * (which would need a {@code DOMException} the engine does not have).
 */
public final class CryptoBuiltins {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_RANDOM_BYTES = 65_536;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CryptoBuiltins() {
    }

    public static JsObject create(InterpreterOps ops) {
        final var crypto = new JsObject();
        Intrinsics.installMethod(crypto, "randomUUID",
                new JsNativeFunction("randomUUID", (_, _) -> new JsString(UUID.randomUUID().toString())));
        Intrinsics.installMethod(crypto, "getRandomValues",
                new JsNativeFunction("getRandomValues", (_, args) -> getRandomValues(arg(args, 0))));
        Intrinsics.installMethod(crypto, "hash", new JsNativeFunction("hash", (_, args) -> hash(args, ops)));
        Intrinsics.installTag(crypto, "Crypto");
        return crypto;
    }

    private static JsValue getRandomValues(JsValue target) {
        if (!(target instanceof JsTypedArray typed) || isFloat(typed.kind())) {
            throw new TypeErrorException("crypto.getRandomValues expects an integer typed array");
        }
        if (typed.byteLength() > MAX_RANDOM_BYTES) {
            throw new RangeErrorException(
                    "crypto.getRandomValues cannot fill more than " + MAX_RANDOM_BYTES + " bytes at once");
        }
        final var bytes = new byte[typed.byteLength()];
        RANDOM.nextBytes(bytes);
        System.arraycopy(bytes, 0, typed.getBuffer().getBytes(), typed.byteOffset(), bytes.length);
        return typed;
    }

    private static boolean isFloat(JsTypedArray.Kind kind) {
        return kind == JsTypedArray.Kind.FLOAT16 || kind == JsTypedArray.Kind.FLOAT32
                || kind == JsTypedArray.Kind.FLOAT64;
    }

    private static JsValue hash(List<JsValue> args, InterpreterOps ops) {
        final var digest = digestFor(JsCoercion.toStr(arg(args, 0), ops));
        final var hashed = digest.digest(bytesOf(arg(args, 1), ops));
        final var encoding = arg(args, 2) instanceof JsUndefined ? "hex" : JsCoercion.toStr(arg(args, 2), ops);
        return switch (encoding) {
            case "hex" -> new JsString(toHex(hashed));
            case "base64" -> new JsString(Base64.getEncoder().encodeToString(hashed));
            default -> throw new TypeErrorException("Unsupported encoding '" + encoding + "': expected hex or base64");
        };
    }

    private static MessageDigest digestFor(String algorithm) {
        final var normalized = switch (algorithm.toLowerCase(Locale.ROOT)) {
            case "sha-1", "sha1" -> "SHA-1";
            case "sha-256", "sha256" -> "SHA-256";
            case "sha-512", "sha512" -> "SHA-512";
            default -> throw new TypeErrorException("Unsupported algorithm '" + algorithm + "'");
        };
        try {
            return MessageDigest.getInstance(normalized);
        } catch (NoSuchAlgorithmException e) {
            throw new TypeErrorException("Unsupported algorithm '" + algorithm + "'");
        }
    }

    private static byte[] bytesOf(JsValue data, InterpreterOps ops) {
        if (data instanceof JsTypedArray typed) {
            final var bytes = new byte[typed.byteLength()];
            System.arraycopy(typed.getBuffer().getBytes(), typed.byteOffset(), bytes, 0, bytes.length);
            return bytes;
        }
        return JsCoercion.toStr(data, ops).getBytes(StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        final var out = new StringBuilder(bytes.length * 2);
        for (final var b : bytes) {
            out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return out.toString();
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
