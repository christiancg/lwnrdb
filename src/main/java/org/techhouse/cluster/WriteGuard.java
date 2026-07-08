package org.techhouse.cluster;

public record WriteGuard(Kind kind, String ownerAddress) {
    public enum Kind {
        ALLOW, NOT_OWNER, NO_QUORUM
    }

    public static WriteGuard allow() {
        return new WriteGuard(Kind.ALLOW, null);
    }

    public static WriteGuard notOwner(String ownerAddress) {
        return new WriteGuard(Kind.NOT_OWNER, ownerAddress);
    }

    public static WriteGuard noQuorum() {
        return new WriteGuard(Kind.NO_QUORUM, null);
    }
}
