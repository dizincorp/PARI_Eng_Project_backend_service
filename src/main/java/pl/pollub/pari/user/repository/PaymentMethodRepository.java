package pl.pollub.pari.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.pollub.pari.user.domain.PaymentMethod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    List<PaymentMethod> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<PaymentMethod> findByIdAndUserId(UUID id, UUID userId);

    Optional<PaymentMethod> findByProviderRef(String providerRef);
}
