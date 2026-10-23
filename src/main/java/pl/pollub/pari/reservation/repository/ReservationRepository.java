package pl.pollub.pari.reservation.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {

    Optional<Reservation> findByCode(String code);

    boolean existsByVehicleId(UUID vehicleId);

    boolean existsBySpotIdAndStatusIn(UUID spotId, Collection<ReservationStatus> statuses);

    boolean existsByVehicleIdAndStatusIn(UUID vehicleId, Collection<ReservationStatus> statuses);

    /** True when a slot-holding reservation of the spot overlaps [start, end). */
    @Query("""
            select count(r) > 0 from Reservation r
            where r.spot.id = :spotId and r.status in :statuses
              and r.period.start < :end and r.period.end > :start
            """)
    boolean existsOverlapping(@Param("spotId") UUID spotId,
                              @Param("statuses") Collection<ReservationStatus> statuses,
                              @Param("start") Instant start,
                              @Param("end") Instant end);

    /** Reservations of the spot that overlap [start, end), used for the month calendar. */
    @Query("""
            select r from Reservation r
            where r.spot.id = :spotId and r.status in :statuses
              and r.period.start < :end and r.period.end > :start
            order by r.period.start
            """)
    List<Reservation> findOverlapping(@Param("spotId") UUID spotId,
                                      @Param("statuses") Collection<ReservationStatus> statuses,
                                      @Param("start") Instant start,
                                      @Param("end") Instant end);

    Page<Reservation> findByDriverId(UUID driverId, Pageable pageable);

    Page<Reservation> findByDriverIdAndStatusIn(UUID driverId, Collection<ReservationStatus> statuses, Pageable pageable);

    /** The reservation shown in the active-session widget (F14). */
    Optional<Reservation> findFirstByDriverIdAndStatusOrderByPeriodStartDesc(UUID driverId, ReservationStatus status);

    /**
     * Owner view (F05): reservations of the owner's spots, each filter optional ({@code null} = no restriction).
     * Built as a Specification because PostgreSQL cannot infer the type of a null timestamp in {@code ? is null}.
     */
    default Page<Reservation> findForOwner(UUID ownerId, UUID spotId, ReservationStatus status,
                                           Instant from, Instant to, Pageable pageable) {
        Specification<Reservation> spec = (root, query, cb) -> cb.equal(root.get("spot").get("owner").get("id"), ownerId);
        if (spotId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("spot").get("id"), spotId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("period").<Instant>get("start"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("period").<Instant>get("start"), to));
        }
        return findAll(spec, pageable);
    }

    /** Unpaid reservations older than the payment timeout (NF08): the scheduler cancels them and frees the slot. */
    List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant createdBefore);

    /** PENDING_APPROVAL requests created at or before {@code createdBefore}, or whose start is not after {@code now} (PARI-38). */
    @Query("""
            select r from Reservation r
            where r.status = pl.pollub.pari.reservation.domain.ReservationStatus.PENDING_APPROVAL
              and (r.createdAt <= :createdBefore or r.period.start <= :now)
            """)
    List<Reservation> findPendingApprovalDueForExpiry(@Param("createdBefore") Instant createdBefore, @Param("now") Instant now);

    /** Drivers still checked in after the end of the reservation (F19). */
    List<Reservation> findByStatusAndPeriodEndBefore(ReservationStatus status, Instant now);

    /** Reminders (F16): reservations that start in the given window. */
    List<Reservation> findByStatusAndPeriodStartBetween(ReservationStatus status, Instant from, Instant to);

    /** Future reservations of a spot, cancelled when a listing or its owner is blocked. */
    List<Reservation> findBySpotIdAndStatusInAndPeriodEndAfter(UUID spotId, Collection<ReservationStatus> statuses, Instant now);

    /** Future reservations of a driver, cancelled when the driver is blocked. */
    List<Reservation> findByDriverIdAndStatusInAndPeriodEndAfter(UUID driverId, Collection<ReservationStatus> statuses, Instant now);

    /** Ended reservations that are still open for reviews and payouts. */
    List<Reservation> findByStatusAndPeriodEndBetween(ReservationStatus status, Instant from, Instant to);
}
