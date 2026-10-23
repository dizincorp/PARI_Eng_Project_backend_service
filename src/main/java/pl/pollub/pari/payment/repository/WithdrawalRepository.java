package pl.pollub.pari.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.payment.domain.Withdrawal;

import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    /** Minor units the owner has already withdrawn. Native because the amounts are mapped through a converter. */
    @Query(value = "SELECT COALESCE(SUM(amount_minor), 0) FROM withdrawals WHERE owner_id = :ownerId AND status = 'COMPLETED'",
            nativeQuery = true)
    long sumCompletedMinor(@Param("ownerId") UUID ownerId);

    /** Serialises concurrent withdrawals of one owner until the transaction ends, so the balance cannot be spent twice. */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(cast(:ownerId as text), 0))) l",
            nativeQuery = true)
    int lockOwner(@Param("ownerId") UUID ownerId);
}
