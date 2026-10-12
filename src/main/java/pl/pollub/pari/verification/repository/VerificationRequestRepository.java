package pl.pollub.pari.verification.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.verification.domain.VerificationRequest;
import pl.pollub.pari.verification.domain.VerificationStatus;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {

    /** The Verifier panel queue (F23), oldest first. */
    Page<VerificationRequest> findByStatusInOrderByRequestedAtAsc(Collection<VerificationStatus> statuses, Pageable pageable);

    List<VerificationRequest> findBySpotIdOrderByRequestedAtDesc(UUID spotId);

    boolean existsBySpotIdAndStatusIn(UUID spotId, Collection<VerificationStatus> statuses);
}
