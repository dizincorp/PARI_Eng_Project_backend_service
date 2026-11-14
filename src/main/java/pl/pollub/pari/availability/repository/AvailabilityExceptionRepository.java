package pl.pollub.pari.availability.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.ExceptionKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AvailabilityExceptionRepository extends JpaRepository<AvailabilityException, UUID> {

    /** Exceptions of a spot that overlap the interval [start, end). */
    @Query("""
            select e from AvailabilityException e
            where e.spot.id = :spotId and e.period.start < :end and e.period.end > :start
            order by e.period.start
            """)
    List<AvailabilityException> findOverlapping(@Param("spotId") UUID spotId,
                                                @Param("start") Instant start,
                                                @Param("end") Instant end);

    @Query("""
            select count(e) > 0 from AvailabilityException e
            where e.spot.id = :spotId and e.kind = :kind and e.period.start < :end and e.period.end > :start
            """)
    boolean existsOverlapping(@Param("spotId") UUID spotId,
                              @Param("kind") ExceptionKind kind,
                              @Param("start") Instant start,
                              @Param("end") Instant end);

    /** A period of this kind that applies to the whole listing (no space named) and overlaps [start, end). */
    @Query("""
            select count(e) > 0 from AvailabilityException e
            where e.spot.id = :spotId and e.kind = :kind and e.unit is null
              and e.period.start < :end and e.period.end > :start
            """)
    boolean existsListingWideOverlapping(@Param("spotId") UUID spotId,
                                         @Param("kind") ExceptionKind kind,
                                         @Param("start") Instant start,
                                         @Param("end") Instant end);

    /** A period of this kind that closes the given space (its own or a listing-wide one) and overlaps [start, end). */
    @Query("""
            select count(e) > 0 from AvailabilityException e
            where e.spot.id = :spotId and e.kind = :kind and (e.unit is null or e.unit.id = :unitId)
              and e.period.start < :end and e.period.end > :start
            """)
    boolean existsOverlappingForUnit(@Param("spotId") UUID spotId,
                                     @Param("kind") ExceptionKind kind,
                                     @Param("unitId") UUID unitId,
                                     @Param("start") Instant start,
                                     @Param("end") Instant end);

    Optional<AvailabilityException> findByIdAndSpotId(UUID id, UUID spotId);
}
