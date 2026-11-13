package pl.pollub.pari.payment.domain;

public enum PaymentType {
    CHARGE,
    PENALTY,
    /** An evacuation fee (PARI-88); kept apart from PENALTY so the two totals never mix. */
    EVAC_FEE,
    REFUND
}
