package pl.pollub.pari.common.service;

import org.springframework.stereotype.Service;
import pl.pollub.pari.common.domain.AuditLogEntry;
import pl.pollub.pari.common.repository.AuditLogRepository;

import java.util.Map;
import java.util.UUID;

/** Writes the append-only audit trail of financial and reservation operations (NF05). Never put personal data in {@code data}. */
@Service
public class AuditTrail {

    private final AuditLogRepository repository;

    public AuditTrail(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID actorId, String entityType, UUID entityId, String action, Map<String, Object> data) {
        repository.save(new AuditLogEntry(actorId, entityType, entityId, action, data));
    }
}
