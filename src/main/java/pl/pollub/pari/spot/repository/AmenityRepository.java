package pl.pollub.pari.spot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.spot.domain.Amenity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AmenityRepository extends JpaRepository<Amenity, UUID> {

    List<Amenity> findByCodeIn(Collection<String> codes);

    List<Amenity> findAllByOrderByLabelAsc();
}
