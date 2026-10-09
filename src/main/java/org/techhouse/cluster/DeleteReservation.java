package org.techhouse.cluster;

public record DeleteReservation(boolean ownerLost, Long version) {
    private static final DeleteReservation NOT_CLUSTERED = new DeleteReservation(false, null);
    private static final DeleteReservation OWNER_LOST = new DeleteReservation(true, null);

    public static DeleteReservation notClustered() {
        return NOT_CLUSTERED;
    }

    public static DeleteReservation lostOwnership() {
        return OWNER_LOST;
    }

    public static DeleteReservation reserved(long version) {
        return new DeleteReservation(false, version);
    }
}
