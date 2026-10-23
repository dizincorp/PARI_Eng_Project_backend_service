package pl.pollub.pari.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where uploaded images live. {@code storageDir} is a directory on a persistent volume (environment variable
 * {@code PARI_MEDIA_DIR}); {@code baseUrl} is the public origin of this backend, prepended to {@code /media/<key>}
 * ({@code PARI_PUBLIC_BASE_URL}).
 */
@ConfigurationProperties("pari.media")
public record MediaProperties(String storageDir, String baseUrl) {

    public MediaProperties {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = "./data/media";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
