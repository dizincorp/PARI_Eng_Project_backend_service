package pl.pollub.pari.common.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.common.domain.AuditLogEntry;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    Page<AuditLogEntry> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId, Pageable pageable);

    /** Oldest first, with the id as a tie-breaker so the order is stable (PARI-84). */
    Page<AuditLogEntry> findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(String entityType, UUID entityId, Pageable pageable);

    Page<AuditLogEntry> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);
}
