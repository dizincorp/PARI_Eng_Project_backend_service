package pl.pollub.pari.availability.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.spot.domain.ParkingSpot;

/** One-off change to the weekly rules: a blocked period ("Zablokuj termin") or an extra open period. */
@Entity
@Table(name = "availability_exceptions")
@Getter
public class AvailabilityException extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    @Embedded
    private TimeRange period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExceptionKind kind;

    private String reason;

    protected AvailabilityException() {
    }

    public AvailabilityException(ParkingSpot spot, TimeRange period, ExceptionKind kind, String reason) {
        this.spot = spot;
        this.period = period;
        this.kind = kind;
        this.reason = reason;
    }
}
