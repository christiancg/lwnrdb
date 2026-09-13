package org.techhouse.listen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class ResultHasher {
    private static final EJson eJson = IocContainer.get(EJson.class);

    private ResultHasher() {
    }

    public static String hash(List<JsonObject> results) {
        final var wrapper = new ResultWrapper(results);
        final var json = eJson.toJson(wrapper);
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static final class ResultWrapper {
        public final List<JsonObject> results;

        private ResultWrapper(List<JsonObject> results) {
            this.results = results;
        }
    }
}
