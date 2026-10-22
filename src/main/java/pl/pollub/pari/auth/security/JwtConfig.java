package pl.pollub.pari.auth.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/** Password hashing and the JWT signing key, encoder and decoder (HS256). Separate from the filter chain so services can use it alone. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** Name of the JWT claim that lists the user roles. */
    public static final String ROLES_CLAIM = "roles";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey jwtKey(JwtProperties properties) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(properties.secret() == null ? "" : properties.secret().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("pari.security.jwt.secret (PARI_JWT_SECRET) is not valid base64", e);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("pari.security.jwt.secret (PARI_JWT_SECRET) must be the base64 of at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
