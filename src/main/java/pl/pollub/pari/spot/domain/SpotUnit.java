package pl.pollub.pari.spot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;

import java.time.Instant;

/**
 * One independently bookable space of a listing (PARI-65, docs/adr/0006). The id identifies it; the label (#1, #2, ...)
 * is only for display. A removed unit stays in the database so old reservations keep their unit and label.
 */
@Entity
@Table(name = "spot_units")
@Getter
public class SpotUnit extends BaseEntity {

    public static final int MAX_LABEL_LENGTH = 20;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    @Column(nullable = false, length = MAX_LABEL_LENGTH)
    private String label;

    /** Creation order inside the listing; the lowest live ordinal is the first unit. Never reused. */
    @Column(nullable = false)
    private int ordinal;

    private Instant removedAt;

    /** Changed by check-in and check-out of a reservation that holds this space. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Occupancy occupancy = Occupancy.FREE;

    protected SpotUnit() {
    }

    SpotUnit(ParkingSpot spot, String label, int ordinal) {
        this.spot = spot;
        this.label = validLabel(label);
        this.ordinal = ordinal;
    }

    public boolean isLive() {
        return removedAt == null;
    }

    public boolean isOccupied() {
        return occupancy == Occupancy.OCCUPIED;
    }

    void occupy() {
        this.occupancy = Occupancy.OCCUPIED;
    }

    void release() {
        this.occupancy = Occupancy.FREE;
    }

    void rename(String newLabel) {
        this.label = validLabel(newLabel);
    }

    void remove(Instant now) {
        this.removedAt = now;
    }

    static String validLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("The label of a space is required");
        }
        String trimmed = label.trim();
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("The label of a space can have at most " + MAX_LABEL_LENGTH + " characters");
        }
        return trimmed;
    }
}
