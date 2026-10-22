package pl.pollub.pari.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT settings. {@code secret} is the base64 of at least 32 random bytes (HS256), environment variable
 * {@code PARI_JWT_SECRET}; the application refuses to start without it. {@code devHeaderAuth} accepts the old
 * X-User-Id header instead of a token and must never be on outside development and tests.
 */
@ConfigurationProperties("pari.security.jwt")
public record JwtProperties(String secret, Duration ttl, boolean devHeaderAuth) {

    public JwtProperties {
        if (ttl == null) {
            ttl = Duration.ofHours(24);
        }
    }
}
