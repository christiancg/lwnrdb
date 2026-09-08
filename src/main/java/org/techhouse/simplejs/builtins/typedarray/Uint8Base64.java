package org.techhouse.simplejs.builtins.typedarray;

import static org.techhouse.simplejs.builtins.Base64Tables.ALPHABETS;
import static org.techhouse.simplejs.builtins.Base64Tables.BASE64URL_DIGITS;
import static org.techhouse.simplejs.builtins.Base64Tables.BASE64_DIGITS;
import static org.techhouse.simplejs.builtins.Base64Tables.CHUNK_HANDLINGS;
import static org.techhouse.simplejs.builtins.Base64Tables.WHITESPACE;
import static org.techhouse.simplejs.builtins.BuiltinArgs.arg;
import static org.techhouse.simplejs.builtins.TypedArrayBuiltins.validate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class Uint8Base64 {
    public interface Decoder {
        Decoded decode(List<JsValue> args, InterpreterOps ops, int maxLength);
    }

    public static JsValue uint8Method(JsTypedArray receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "toBase64" ->
                new JsNativeFunction("toBase64", (_, args) -> new JsString(toBase64(receiver, args, ops)));
            case "toHex" -> new JsNativeFunction("toHex", (_, _) -> new JsString(toHex(receiver)));
            case "setFromBase64" ->
                new JsNativeFunction("setFromBase64", (_, args) -> setFrom(receiver, args, ops, Uint8Base64::base64));
            case "setFromHex" ->
                new JsNativeFunction("setFromHex", (_, args) -> setFrom(receiver, args, ops, Uint8Base64::hex));
            default -> null;
        };
    }

    public static byte[] bytesOf(JsTypedArray typed) {
        validate(typed);
        final var bytes = new byte[typed.length()];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (int) JsCoercion.toNumber(typed.getElement(i));
        }
        return bytes;
    }

    public static String toBase64(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops) {
        final var options = arg(args, 0);
        final var alphabet = optionString(options, "alphabet", "base64", ALPHABETS, ops);
        final var omitPadding = ops == null || !InterpreterUtils.isObjectLike(options)
                ? JsBoolean.FALSE
                : ops.getMember(options, new JsString("omitPadding"));
        var encoder = "base64url".equals(alphabet) ? Base64.getUrlEncoder() : Base64.getEncoder();
        if (JsCoercion.toBoolean(omitPadding)) {
            encoder = encoder.withoutPadding();
        }
        return encoder.encodeToString(bytesOf(receiver));
    }

    public static String toHex(JsTypedArray receiver) {
        final var hex = new StringBuilder();
        for (final var value : bytesOf(receiver)) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    public static String optionString(JsValue options, String key, String fallback, List<String> allowed,
            InterpreterOps ops) {
        if (ops == null || !InterpreterUtils.isObjectLike(options)) {
            if (!(options instanceof JsUndefined) && ops != null) {
                throw new TypeErrorException("Options must be an object");
            }
            return fallback;
        }
        final var value = ops.getMember(options, new JsString(key));
        if (value instanceof JsUndefined) {
            return fallback;
        }
        if (!(value instanceof JsString text) || !allowed.contains(text.getValue())) {
            throw new TypeErrorException("Invalid " + key + " option");
        }
        return text.getValue();
    }

    public record Decoded(int read, byte[] bytes, RuntimeException error) {
    }

    public static String requireSource(List<JsValue> args) {
        if (!(arg(args, 0) instanceof JsString source)) {
            throw new TypeErrorException("Expected a string to decode");
        }
        return source.getValue();
    }

    public static Decoded base64(List<JsValue> args, InterpreterOps ops, int maxLength) {
        final var source = requireSource(args);
        final var options = arg(args, 1);
        final var alphabet = optionString(options, "alphabet", "base64", ALPHABETS, ops);
        final var lastChunk = optionString(options, "lastChunkHandling", "loose", CHUNK_HANDLINGS, ops);
        chargeDecode(source, ops);
        return fromBase64(source, alphabet, lastChunk, maxLength);
    }

    public static Decoded hex(List<JsValue> args, InterpreterOps ops, int maxLength) {
        final var source = requireSource(args);
        if (ops != null && !(arg(args, 1) instanceof JsUndefined) && !InterpreterUtils.isObjectLike(arg(args, 1))) {
            throw new TypeErrorException("Options must be an object");
        }
        chargeDecode(source, ops);
        return fromHex(source, maxLength);
    }

    public static JsValue decodeAll(Decoded decoded, InterpreterOps ops) {
        if (decoded.error() != null) {
            throw decoded.error();
        }
        return new JsTypedArray(JsTypedArray.Kind.UINT8, new JsArrayBuffer(decoded.bytes()), 0, decoded.bytes().length)
                .withOps(ops);
    }

    public static JsValue setFrom(JsTypedArray receiver, List<JsValue> args, InterpreterOps ops, Decoder decoder) {
        validate(receiver);
        final var decoded = decoder.decode(args, ops, receiver.length());
        validate(receiver);
        for (var i = 0; i < decoded.bytes().length && i < receiver.length(); i++) {
            receiver.setElement(i, new JsNumber(decoded.bytes()[i] & 0xFF), ops);
        }
        if (decoded.error() != null) {
            throw decoded.error();
        }
        final var result = new JsObject();
        result.set("read", new JsNumber(decoded.read()));
        result.set("written", new JsNumber(decoded.bytes().length));
        return result;
    }

    public static Decoded fromBase64(String source, String alphabet, String lastChunkHandling, int maxLength) {
        if (maxLength == 0) {
            return new Decoded(0, new byte[0], null);
        }
        final var digits = "base64url".equals(alphabet) ? BASE64URL_DIGITS : BASE64_DIGITS;
        final var bytes = new ArrayList<Byte>();
        final var chunk = new StringBuilder();
        var read = 0;
        var index = 0;
        while (true) {
            index = skipWhitespace(source, index);
            if (index == source.length()) {
                return endOfBase64(source, lastChunkHandling, bytes, chunk, read);
            }
            final var character = source.charAt(index);
            index++;
            if (character == '=') {
                return closeBase64(source, lastChunkHandling, digits, bytes, chunk, read, index);
            }
            if (digits.indexOf(character) < 0) {
                return new Decoded(read, toArray(bytes), new SyntaxErrorException("Invalid base64 character"));
            }
            final var remaining = maxLength - bytes.size();
            if (remaining == 1 && chunk.length() == 2 || remaining == 2 && chunk.length() == 3) {
                return new Decoded(read, toArray(bytes), null);
            }
            chunk.append(character);
            if (chunk.length() == 4) {
                decodeChunk(digits, chunk.toString(), false, bytes);
                chunk.setLength(0);
                read = index;
                if (bytes.size() == maxLength) {
                    return new Decoded(read, toArray(bytes), null);
                }
            }
        }
    }

    public static Decoded endOfBase64(String source, String lastChunkHandling, List<Byte> bytes, StringBuilder chunk,
            int read) {
        if (chunk.isEmpty()) {
            return new Decoded(source.length(), toArray(bytes), null);
        }
        if ("stop-before-partial".equals(lastChunkHandling)) {
            return new Decoded(read, toArray(bytes), null);
        }
        if ("strict".equals(lastChunkHandling) || chunk.length() == 1) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Incomplete base64 chunk"));
        }
        decodeChunk(BASE64_DIGITS, chunk.toString(), false, bytes);
        return new Decoded(source.length(), toArray(bytes), null);
    }

    public static Decoded closeBase64(String source, String lastChunkHandling, String digits, List<Byte> bytes,
            StringBuilder chunk, int read, int afterPad) {
        if (chunk.length() < 2) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Unexpected base64 padding"));
        }
        var index = skipWhitespace(source, afterPad);
        if (chunk.length() == 2) {
            if (index == source.length()) {
                return "stop-before-partial".equals(lastChunkHandling)
                        ? new Decoded(read, toArray(bytes), null)
                        : new Decoded(read, toArray(bytes), new SyntaxErrorException("Incomplete base64 padding"));
            }
            if (source.charAt(index) != '=') {
                return new Decoded(read, toArray(bytes), new SyntaxErrorException("Invalid base64 padding"));
            }
            index = skipWhitespace(source, index + 1);
        }
        if (index != source.length()) {
            return new Decoded(read, toArray(bytes), new SyntaxErrorException("Unexpected base64 trailing data"));
        }
        try {
            decodeChunk(digits, chunk.toString(), "strict".equals(lastChunkHandling), bytes);
        } catch (SyntaxErrorException error) {
            return new Decoded(read, toArray(bytes), error);
        }
        return new Decoded(source.length(), toArray(bytes), null);
    }

    public static void decodeChunk(String digits, String chunk, boolean throwOnExtraBits, List<Byte> bytes) {
        var accumulated = 0;
        for (var i = 0; i < chunk.length(); i++) {
            accumulated = accumulated << 6 | digits.indexOf(chunk.charAt(i));
        }
        switch (chunk.length()) {
            case 2 -> {
                if (throwOnExtraBits && (accumulated & 0xF) != 0) {
                    throw new SyntaxErrorException("Extra bits in base64 padding");
                }
                bytes.add((byte) (accumulated >> 4));
            }
            case 3 -> {
                if (throwOnExtraBits && (accumulated & 0x3) != 0) {
                    throw new SyntaxErrorException("Extra bits in base64 padding");
                }
                bytes.add((byte) (accumulated >> 10));
                bytes.add((byte) (accumulated >> 2));
            }
            default -> {
                bytes.add((byte) (accumulated >> 16));
                bytes.add((byte) (accumulated >> 8));
                bytes.add((byte) accumulated);
            }
        }
    }

    public static Decoded fromHex(String source, int maxLength) {
        final var bytes = new ArrayList<Byte>();
        if (source.length() % 2 != 0) {
            return new Decoded(0, new byte[0], new SyntaxErrorException("Invalid hex string length"));
        }
        var index = 0;
        while (index < source.length() && bytes.size() < maxLength) {
            final var high = Character.digit(source.charAt(index), 16);
            final var low = Character.digit(source.charAt(index + 1), 16);
            if (high < 0 || low < 0 || isNotAsciiHex(source.charAt(index)) || isNotAsciiHex(source.charAt(index + 1))) {
                return new Decoded(index, toArray(bytes), new SyntaxErrorException("Invalid hex string"));
            }
            index += 2;
            bytes.add((byte) (high << 4 | low));
        }
        return new Decoded(index, toArray(bytes), null);
    }

    public static boolean isNotAsciiHex(char character) {
        return (character < '0' || character > '9') && (character < 'a' || character > 'f')
                && (character < 'A' || character > 'F');
    }

    public static int skipWhitespace(String source, int from) {
        var index = from;
        while (index < source.length() && WHITESPACE.indexOf(source.charAt(index)) >= 0) {
            index++;
        }
        return index;
    }

    public static byte[] toArray(List<Byte> bytes) {
        final var result = new byte[bytes.size()];
        for (var i = 0; i < result.length; i++) {
            result[i] = bytes.get(i);
        }
        return result;
    }

    public static void chargeDecode(String source, InterpreterOps ops) {
        InterpreterOps.chargeElements(ops, source.length());
    }

    private Uint8Base64() {
    }
}
