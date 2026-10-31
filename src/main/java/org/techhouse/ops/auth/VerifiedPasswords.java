package org.techhouse.ops.auth;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.techhouse.config.Configuration;

public final class VerifiedPasswords {
    public static final int MAX_USERS = AuthFailureThrottle.MAX_KEYS;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final char SEPARATOR = '\0';
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Configuration configuration = Configuration.getInstance();

    private final SecretKeySpec key;
    private final Map<String, byte[]> verifiers;

    public VerifiedPasswords() {
        this(MAX_USERS);
    }

    public VerifiedPasswords(int maxUsers) {
        this.key = new SecretKeySpec(randomKey(), ALGORITHM);
        this.verifiers = new BoundedVerifiers(maxUsers);
    }

    private static byte[] randomKey() {
        final var bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public boolean matches(String username, String storedHash, String password) {
        if (!configuration.isAuthVerifierCacheEnabled()) {
            return false;
        }
        final var known = lookup(username);
        return known != null && MessageDigest.isEqual(known, verifierOf(storedHash, password));
    }

    public void remember(String username, String storedHash, String password) {
        if (configuration.isAuthVerifierCacheEnabled()) {
            store(username, verifierOf(storedHash, password));
        }
    }

    public synchronized int size() {
        return verifiers.size();
    }

    private synchronized byte[] lookup(String username) {
        return verifiers.get(username);
    }

    private synchronized void store(String username, byte[] verifier) {
        verifiers.put(username, verifier);
    }

    private byte[] verifierOf(String storedHash, String password) {
        try {
            final var mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal((storedHash + SEPARATOR + password).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        }
    }

    private static final class BoundedVerifiers extends LinkedHashMap<String, byte[]> {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int maxUsers;

        private BoundedVerifiers(int maxUsers) {
            super(16, 0.75f, true);
            this.maxUsers = maxUsers;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > maxUsers;
        }
    }
}
