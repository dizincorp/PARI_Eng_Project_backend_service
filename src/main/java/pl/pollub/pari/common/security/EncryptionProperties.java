package pl.pollub.pari.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Encryption settings. {@code dataEncryptionKey} is a base64-encoded 32-byte AES-256 key
 * (property {@code pari.security.data-encryption-key}, environment variable {@code PARI_DATA_KEY}).
 */
@ConfigurationProperties("pari.security")
public record EncryptionProperties(String dataEncryptionKey) {
}
