package pl.pollub.pari.common.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import pl.pollub.pari.common.domain.AuditLogEntry;
import pl.pollub.pari.support.DbTestBase;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogRepositoryTest extends DbTestBase {

    @Autowired
    AuditLogRepository auditLog;

    @Test
    void storesStructuredDataAndFindsByEntity() {
        UUID reservationId = UUID.randomUUID();
        auditLog.save(new AuditLogEntry(null, "Reservation", reservationId, "CONFIRMED", Map.of("totalMinor", 2100, "by", "webhook")));
        auditLog.save(new AuditLogEntry(null, "Reservation", UUID.randomUUID(), "CONFIRMED", Map.of()));
        flushAndClear();

        var page = auditLog.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("Reservation", reservationId, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        AuditLogEntry entry = page.getContent().get(0);
        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getCreatedAt()).isNotNull();
        assertThat(entry.getData()).containsEntry("by", "webhook").containsEntry("totalMinor", 2100);
    }

    @Test
    void updatesAreRejectedByTheDatabase() {
        auditLog.saveAndFlush(new AuditLogEntry(null, "Payment", UUID.randomUUID(), "SUCCEEDED", Map.of()));

        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'TAMPERED'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deletesAreRejectedByTheDatabase() {
        auditLog.saveAndFlush(new AuditLogEntry(null, "Payment", UUID.randomUUID(), "SUCCEEDED", Map.of()));

        assertThatThrownBy(() -> jdbc.update("delete from audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
