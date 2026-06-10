package org.techhouse.data.auth;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";

    private PasswordHasher() {
    }

    public static String hash(String plain) {
        try {
            final var random = new SecureRandom();
            final var salt = new byte[SALT_BYTES];
            random.nextBytes(salt);

            final var factory = SecretKeyFactory.getInstance(ALGORITHM);
            final var spec = new PBEKeySpec(plain.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            final var hash = factory.generateSecret(spec).getEncoded();

            final var saltB64 = Base64.getEncoder().encodeToString(salt);
            final var hashB64 = Base64.getEncoder().encodeToString(hash);

            return String.format("%s$%d$%s$%s", PREFIX, ITERATIONS, saltB64, hashB64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    public static boolean verify(String plain, String stored) {
        try {
            if (stored == null || !stored.startsWith(PREFIX + "$")) {
                return false;
            }

            final var parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }

            final var iterations = Integer.parseInt(parts[1]);
            final var saltB64 = parts[2];
            final var hashB64 = parts[3];

            final var salt = Base64.getDecoder().decode(saltB64);
            final var storedHash = Base64.getDecoder().decode(hashB64);

            final var factory = SecretKeyFactory.getInstance(ALGORITHM);
            final var spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, KEY_LENGTH);
            final var computedHash = factory.generateSecret(spec).getEncoded();

            return constantTimeCompare(storedHash, computedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeCompare(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
