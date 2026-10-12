package pl.pollub.pari.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.user.domain.Vehicle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    List<Vehicle> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<Vehicle> findByIdAndUserId(UUID id, UUID userId);

    Optional<Vehicle> findByUserIdAndPlateNumber(UUID userId, String plateNumber);
}
