package pl.pollub.pari.spot.domain;

public enum SpotStatus {
    DRAFT,
    ACTIVE,
    INACTIVE,
    BLOCKED,
    /** Removed by the owner; kept only so past reservations and payments stay consistent. Never listed, never reactivated. */
    ARCHIVED
}
