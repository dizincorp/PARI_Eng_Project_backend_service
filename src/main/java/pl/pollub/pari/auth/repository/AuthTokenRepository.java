package pl.pollub.pari.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.pollub.pari.auth.domain.AuthToken;
import pl.pollub.pari.auth.domain.AuthTokenType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {

    Optional<AuthToken> findByTypeAndTokenHash(AuthTokenType type, String tokenHash);

    /** Revokes all unused tokens of a type (for example every refresh token after a password change). */
    @Modifying
    @Query("update AuthToken t set t.usedAt = :now where t.user.id = :userId and t.type = :type and t.usedAt is null")
    int invalidateAll(@Param("userId") UUID userId, @Param("type") AuthTokenType type, @Param("now") Instant now);

    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
