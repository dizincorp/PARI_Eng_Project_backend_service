package pl.pollub.pari.common.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedStringConverterTest {

    private static String key(String thirtyTwoChars) {
        return Base64.getEncoder().encodeToString(thirtyTwoChars.getBytes(StandardCharsets.US_ASCII));
    }

    private static final String KEY_A = key("0123456789abcdef0123456789abcdef");
    private static final String KEY_B = key("fedcba9876543210fedcba9876543210");

    @Test
    void roundTripsAndNeverStoresPlainText() {
        EncryptedStringConverter converter = new EncryptedStringConverter(new EncryptionProperties(KEY_A));

        String stored = converter.convertToDatabaseColumn("+48 600 100 200");

        assertThat(stored).doesNotContain("600");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("+48 600 100 200");
    }

    @Test
    void usesAFreshIvForEveryValue() {
        EncryptedStringConverter converter = new EncryptedStringConverter(new EncryptionProperties(KEY_A));

        assertThat(converter.convertToDatabaseColumn("same")).isNotEqualTo(converter.convertToDatabaseColumn("same"));
    }

    @Test
    void passesNullThrough() {
        EncryptedStringConverter converter = new EncryptedStringConverter(new EncryptionProperties(KEY_A));

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String stored = new EncryptedStringConverter(new EncryptionProperties(KEY_A)).convertToDatabaseColumn("secret");
        EncryptedStringConverter other = new EncryptedStringConverter(new EncryptionProperties(KEY_B));

        assertThatThrownBy(() -> other.convertToEntityAttribute(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedDataIsRejected() {
        EncryptedStringConverter converter = new EncryptedStringConverter(new EncryptionProperties(KEY_A));
        byte[] bytes = Base64.getDecoder().decode(converter.convertToDatabaseColumn("secret"));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> converter.convertToEntityAttribute(Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesMissingOrMalformedKeys() {
        assertThatThrownBy(() -> new EncryptedStringConverter(new EncryptionProperties("")))
                .hasMessageContaining("not set");
        assertThatThrownBy(() -> new EncryptedStringConverter(new EncryptionProperties("%%%")))
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> new EncryptedStringConverter(new EncryptionProperties(key("short"))))
                .hasMessageContaining("5 bytes");
    }
}
