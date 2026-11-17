package io.github.christiancg.lwnrdb.client;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class TlsSettings {
    public static final String DEFAULT_TRUST_STORE_TYPE = "PKCS12";

    private final Path trustStore;
    private final char[] trustStorePassword;
    private final String trustStoreType;
    private final boolean insecure;

    private TlsSettings(Path trustStore, char[] trustStorePassword, String trustStoreType, boolean insecure) {
        this.trustStore = trustStore;
        this.trustStorePassword = trustStorePassword == null ? null : trustStorePassword.clone();
        this.trustStoreType = trustStoreType;
        this.insecure = insecure;
    }

    public static TlsSettings systemTrust() {
        return new TlsSettings(null, null, null, false);
    }

    public static TlsSettings trustStore(Path path, char[] password) {
        return trustStore(path, password, DEFAULT_TRUST_STORE_TYPE);
    }

    public static TlsSettings trustStore(Path path, char[] password, String type) {
        return new TlsSettings(Objects.requireNonNull(path, "path"), password, Objects.requireNonNull(type, "type"),
                false);
    }

    public static TlsSettings insecure() {
        return new TlsSettings(null, null, null, true);
    }

    public Optional<Path> trustStorePath() {
        return Optional.ofNullable(trustStore);
    }

    public char[] trustStorePassword() {
        return trustStorePassword == null ? null : trustStorePassword.clone();
    }

    public String trustStoreType() {
        return trustStoreType;
    }

    public boolean isInsecure() {
        return insecure;
    }
}
