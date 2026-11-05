package org.techhouse.utils;

import java.security.SecureRandom;
import java.util.Objects;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public final class SaltedKey {
    private static final long SEED = seedFrom(new SecureRandom());
    private static final long MULTIPLIER = 0x9E3779B97F4A7C15L;
    private static final long WORD_KEY = mix(SEED) | 1L;
    private static final int ARRAY_STEP = 31;
    private static final int CHARS_PER_ROUND = 4;
    private final JsonBaseElement element;
    private final int hash;

    public SaltedKey(JsonBaseElement element) {
        this.element = element;
        this.hash = Long.hashCode(saltedHash(element));
    }

    private static long seedFrom(SecureRandom random) {
        return random.nextLong();
    }

    public JsonBaseElement element() {
        return element;
    }

    public static int memberHash(String key, JsonBaseElement value) {
        return Long.hashCode(mix(stringHash(key) ^ saltedHash(value)));
    }

    private static long saltedHash(JsonBaseElement element) {
        return switch (element) {
            case null -> SEED;
            case JsonString string -> stringHash(string.getValue());
            case JsonNumber number -> numberHash(number.doubleValue());
            case JsonArray array -> arrayHash(array);
            case JsonObject object -> objectHash(object);
            default -> mix(SEED ^ element.hashCode());
        };
    }

    private static long stringHash(String value) {
        return value == null ? SEED : seededStringHash(SEED, WORD_KEY, value);
    }

    @SuppressWarnings("SameParameterValue")
    private static long seededStringHash(long seed, long wordKey, String value) {
        final var length = value.length();
        var hash = seed ^ length;
        var i = 0;
        for (; i + CHARS_PER_ROUND <= length; i += CHARS_PER_ROUND) {
            hash = absorb(hash ^ fourCharsAt(value, i), wordKey);
        }
        for (; i < length; i++) {
            hash = mix(hash ^ value.charAt(i));
        }
        return hash;
    }

    private static long absorb(long input, long wordKey) {
        return input * wordKey ^ Math.unsignedMultiplyHigh(input, wordKey);
    }

    private static long fourCharsAt(String value, int index) {
        return value.charAt(index) | (long) value.charAt(index + 1) << Character.SIZE
                | (long) value.charAt(index + 2) << 2 * Character.SIZE
                | (long) value.charAt(index + 3) << 3 * Character.SIZE;
    }

    private static long numberHash(double number) {
        return mix(SEED ^ Double.doubleToLongBits(number == 0.0 ? 0.0 : number));
    }

    private static long arrayHash(JsonArray array) {
        var hash = SEED;
        for (var element : array) {
            hash = mix(hash * ARRAY_STEP + saltedHash(element));
        }
        return hash;
    }

    private static long objectHash(JsonObject object) {
        var hash = SEED;
        for (var member : object.entrySet()) {
            hash += mix(stringHash(member.getKey()) ^ saltedHash(member.getValue()));
        }
        return hash;
    }

    private static long mix(long value) {
        var mixed = value * MULTIPLIER;
        mixed ^= mixed >>> 32;
        mixed *= MULTIPLIER;
        return mixed ^ (mixed >>> 29);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SaltedKey that && hash == that.hash && Objects.equals(element, that.element);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
