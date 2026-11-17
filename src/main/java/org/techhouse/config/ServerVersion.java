package org.techhouse.config;

public final class ServerVersion {
    public static final String UNPACKAGED = "dev";

    private ServerVersion() {
    }

    public static String current() {
        final var packaged = ServerVersion.class.getPackage().getImplementationVersion();
        return packaged == null ? UNPACKAGED : packaged;
    }
}
