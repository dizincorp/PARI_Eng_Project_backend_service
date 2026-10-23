package pl.pollub.pari.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One-way fingerprint of a phone number, so "is this number already used" can be answered although the number is
 * stored encrypted with a random IV. HMAC-SHA256 keyed with the data encryption key; the number cannot be read from the hash.
 */
@Component
@EnableConfigurationProperties(EncryptionProperties.class)
public class PhoneHasher {

    private final SecretKeySpec key;

    public PhoneHasher(EncryptionProperties properties) {
        String base64 = properties.dataEncryptionKey();
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("pari.security.data-encryption-key (PARI_DATA_KEY) is not set");
        }
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64.trim()), "HmacSHA256");
    }

    /** Removes spaces, dashes, dots and brackets, so "+48 501-234-567" and "+48501234567" are the same number. */
    public static String normalize(String phone) {
        return phone.replaceAll("[\\s().-]", "");
    }

    public String hash(String phone) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalize(phone).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Hashing failed", e);
        }
    }
}
