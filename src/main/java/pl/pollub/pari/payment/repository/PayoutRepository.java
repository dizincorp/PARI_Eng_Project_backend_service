package pl.pollub.pari.payment.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.payment.domain.Payout;
import pl.pollub.pari.payment.domain.PayoutStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    Optional<Payout> findByReservationId(UUID reservationId);

    /** Payouts the scheduler has to release now (F17). */
    List<Payout> findByStatusAndReleaseAtLessThanEqual(PayoutStatus status, Instant now);

    Page<Payout> findByOwnerIdOrderByReleaseAtDesc(UUID ownerId, Pageable pageable);

    /**
     * Sum of the owner's net payouts in minor units for a status and a release period (F06). Native because the amounts
     * are mapped through a converter.
     */
    @Query(value = """
            SELECT COALESCE(SUM(net_minor), 0) FROM payouts
            WHERE owner_id = :ownerId AND status = :status AND release_at >= :from AND release_at < :to
            """, nativeQuery = true)
    long sumNetMinor(@Param("ownerId") UUID ownerId,
                     @Param("status") String status,
                     @Param("from") Instant from,
                     @Param("to") Instant to);
}
