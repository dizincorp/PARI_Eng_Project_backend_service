package pl.pollub.pari.availability.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.availability.domain.AvailabilityRule;

import java.util.List;
import java.util.UUID;

public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, UUID> {

    List<AvailabilityRule> findBySpotIdOrderByDayOfWeekAscStartTimeAsc(UUID spotId);

    List<AvailabilityRule> findBySpotIdAndDayOfWeek(UUID spotId, int dayOfWeek);

    /** Used when the owner replaces the whole weekly schedule. */
    @Modifying
    @Query("delete from AvailabilityRule r where r.spot.id = :spotId")
    int deleteAllBySpotId(@Param("spotId") UUID spotId);
}
