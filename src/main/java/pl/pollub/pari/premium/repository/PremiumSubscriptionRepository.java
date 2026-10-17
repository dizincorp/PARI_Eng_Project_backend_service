package pl.pollub.pari.premium.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.premium.domain.PremiumSubscription;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PremiumSubscriptionRepository extends JpaRepository<PremiumSubscription, UUID> {

    /** The owner's plan that is active at {@code now}, if any (cancelled plans stay usable until they expire). */
    @Query("""
            select p from PremiumSubscription p
            where p.owner.id = :ownerId and p.status in (pl.pollub.pari.premium.domain.SubscriptionStatus.ACTIVE,
                                                         pl.pollub.pari.premium.domain.SubscriptionStatus.CANCELLED)
              and p.expiresAt > :now
            order by p.expiresAt desc
            """)
    List<PremiumSubscription> findCurrent(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    default Optional<PremiumSubscription> findCurrentOne(UUID ownerId, Instant now) {
        return findCurrent(ownerId, now).stream().findFirst();
    }

    /** Plans past their end that the daily job has to expire. */
    @Query("select p from PremiumSubscription p where p.status <> pl.pollub.pari.premium.domain.SubscriptionStatus.EXPIRED and p.expiresAt <= :now")
    List<PremiumSubscription> findDueForExpiry(@Param("now") Instant now);
}
