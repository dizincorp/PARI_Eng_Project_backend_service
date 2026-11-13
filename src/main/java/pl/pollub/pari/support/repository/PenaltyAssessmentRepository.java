package pl.pollub.pari.support.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.support.domain.PenaltyAssessment;
import pl.pollub.pari.support.domain.PenaltyState;

import java.util.Optional;
import java.util.UUID;

public interface PenaltyAssessmentRepository extends JpaRepository<PenaltyAssessment, UUID> {

    Optional<PenaltyAssessment> findByOperationKey(String operationKey);

    boolean existsByTicketIdAndState(UUID ticketId, PenaltyState state);
}
