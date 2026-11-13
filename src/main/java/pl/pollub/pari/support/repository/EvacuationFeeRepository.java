package pl.pollub.pari.support.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.support.domain.EvacuationFee;

import java.util.Optional;
import java.util.UUID;

public interface EvacuationFeeRepository extends JpaRepository<EvacuationFee, UUID> {

    Optional<EvacuationFee> findByOperationKey(String operationKey);

    boolean existsByRefundsId(UUID feeId);
}
