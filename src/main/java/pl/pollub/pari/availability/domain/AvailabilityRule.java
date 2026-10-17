package pl.pollub.pari.availability.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.TimeRange;
import pl.pollub.pari.spot.domain.ParkingSpot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Recurring weekly availability: the space can be booked on {@code dayOfWeek} between startTime and endTime (local time). */
@Entity
@Table(name = "availability_rules")
@Getter
public class AvailabilityRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    /** ISO day of week: 1 = Monday ... 7 = Sunday. */
    @Column(nullable = false)
    private int dayOfWeek;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private LocalDate validFrom;

    private LocalDate validTo;

    protected AvailabilityRule() {
    }

    public AvailabilityRule(ParkingSpot spot, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                            LocalDate validFrom, LocalDate validTo) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new IllegalArgumentException("dayOfWeek must be 1..7");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        this.spot = spot;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /**
     * True when the whole range lies inside this rule. Only ranges within one local day (or ending exactly at midnight
     * of the next day) can be covered by a single rule; longer reservations are checked day by day by the service.
     */
    public boolean covers(TimeRange range, ZoneId zone) {
        ZonedDateTime start = range.start().atZone(zone);
        ZonedDateTime end = range.end().atZone(zone);
        LocalDate day = start.toLocalDate();
        if (start.getDayOfWeek().getValue() != dayOfWeek) {
            return false;
        }
        if ((validFrom != null && day.isBefore(validFrom)) || (validTo != null && day.isAfter(validTo))) {
            return false;
        }
        LocalTime endLocal;
        if (end.toLocalDate().equals(day)) {
            endLocal = end.toLocalTime();
        } else if (end.toLocalDate().equals(day.plusDays(1)) && end.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            endLocal = LocalTime.MAX;
        } else {
            return false;
        }
        return !start.toLocalTime().isBefore(startTime) && !endLocal.isAfter(endTime);
    }
}
