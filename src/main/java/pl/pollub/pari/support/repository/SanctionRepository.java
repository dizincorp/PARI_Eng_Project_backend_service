package pl.pollub.pari.support.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.support.domain.Sanction;
import pl.pollub.pari.support.domain.SanctionType;

import java.util.List;
import java.util.UUID;

public interface SanctionRepository extends JpaRepository<Sanction, UUID> {

    List<Sanction> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndType(UUID userId, SanctionType type);
}
