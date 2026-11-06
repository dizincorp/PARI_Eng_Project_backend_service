package pl.pollub.pari.payment.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.payment.domain.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Idempotency for provider webhooks. */
    Optional<Payment> findByProviderRef(String providerRef);

    List<Payment> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);

    List<Payment> findByReservationIdIn(java.util.Collection<UUID> reservationIds);

    /** Transaction history of a user (as the driver), for support disputes (F22). */
    @Query("select p from Payment p where p.reservation.driver.id = :userId order by p.createdAt desc")
    Page<Payment> findByDriverId(@Param("userId") UUID userId, Pageable pageable);
}
