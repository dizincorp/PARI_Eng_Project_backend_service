package pl.pollub.pari.support.domain;

/** Lifecycle of a support-assessed penalty (PARI-87). PENDING is the only state that can still change. */
public enum PenaltyState {
    /** Assessed, nothing collected yet. Support can charge or waive it. */
    PENDING,
    /** Collected from the driver. */
    CHARGED,
    /** Support decided not to collect it. */
    WAIVED,
    /** Collection was attempted and the provider (or the missing payment method) refused. */
    FAILED
}
