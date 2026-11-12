package pl.pollub.pari.support.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportStatus;

import pl.pollub.pari.support.domain.TicketType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** Reports a user filed, is the subject of, or takes part in as the driver or the owner of the reservation. */
    @Query("""
            select r from Report r left join r.reporter rep left join r.reportedUser ru
            where rep.id = :userId or ru.id = :userId or r.reservation.driver.id = :userId
               or r.reservation.spot.owner.id = :userId
            order by r.createdAt desc
            """)
    Page<Report> findInvolving(@Param("userId") UUID userId, Pageable pageable);

    /** Support panel (F21): optional filters on status, category and assignee. */
    @Query("""
            select r from Report r
            where (:status is null or r.status = :status)
              and (:category is null or r.category = :category)
              and (:assignedTo is null or r.assignedTo.id = :assignedTo)
            order by r.createdAt desc
            """)
    Page<Report> search(@Param("status") ReportStatus status,
                        @Param("category") String category,
                        @Param("assignedTo") UUID assignedTo,
                        Pageable pageable);

    boolean existsByReservationIdAndTypeAndStatusIn(UUID reservationId, TicketType type, Collection<ReportStatus> statuses);

    boolean existsByReservationIdAndType(UUID reservationId, TicketType type);

    List<Report> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);

    /** Support queue (PARI-70): high priority first, then oldest first. Filters are optional. */
    @Query("""
            select r from Report r
            where (:status is null or r.status = :status)
              and (:type is null or r.type = :type)
              and (:priority is null or r.priority = :priority)
              and (:onlyUnassigned = false or r.assignedTo is null)
            order by case when r.priority = pl.pollub.pari.support.domain.TicketPriority.HIGH then 0 else 1 end, r.createdAt asc
            """)
    Page<Report> queue(@Param("status") ReportStatus status,
                       @Param("type") TicketType type,
                       @Param("priority") pl.pollub.pari.support.domain.TicketPriority priority,
                       @Param("onlyUnassigned") boolean onlyUnassigned,
                       Pageable pageable);

    /** True while an open report keeps the payout of the reservation on hold. */
    boolean existsByReservationIdAndStatusIn(UUID reservationId, Collection<ReportStatus> statuses);
}
