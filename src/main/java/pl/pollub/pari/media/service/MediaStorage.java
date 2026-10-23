package pl.pollub.pari.media.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import pl.pollub.pari.media.MediaProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates images and keeps them in a directory on the local (persistent) volume. The file name chosen by the client
 * is never used: the stored name is a random UUID plus the extension of the type found in the file's own bytes.
 */
@Component
public class MediaStorage {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    /** The only shape of key this class produces and accepts, so a key can never point outside the storage directory. */
    private static final Pattern KEY = Pattern.compile(
            "^(spots|avatars)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");
    private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

    /** A stored file; {@code storageKey} is relative to the storage directory and fits {@code spot_photos.storage_key}. */
    public record StoredMedia(UUID id, String storageKey, String url, String originalFilename, String mimeType, long sizeBytes) {
    }

    private final Path root;
    private final String baseUrl;

    public MediaStorage(MediaProperties properties) {
        this.root = Path.of(properties.storageDir()).toAbsolutePath().normalize();
        this.baseUrl = properties.baseUrl();
    }

    public StoredMedia store(MediaCategory category, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new FileTooLargeException("The file is larger than 5 MB");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(declared)) {
            throw new IllegalArgumentException("Only image/jpeg, image/png and image/webp files are accepted");
        }
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String detected = detectType(Arrays.copyOf(head, read));
        if (detected == null || !detected.equals(declared)) {
            throw new IllegalArgumentException("The file content is not a valid " + declared + " image");
        }

        UUID id = UUID.randomUUID();
        String key = category.folder() + "/" + id + "." + extension(detected);
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StoredMedia(id, key, urlOf(key), displayName(file.getOriginalFilename()), detected, file.getSize());
    }

    /** Removes a stored file; a key that is not ours (an external URL, a legacy value) is ignored. Never throws. */
    public void delete(String storageKey) {
        if (storageKey == null || !KEY.matcher(storageKey).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Could not delete media file {}", storageKey, e);
        }
    }

    public String urlOf(String storageKey) {
        return baseUrl + "/media/" + storageKey;
    }

    /** The storage key behind one of our public URLs, or {@code null} for any other URL. */
    public String keyOfUrl(String url) {
        String prefix = baseUrl + "/media/";
        if (url == null || !url.startsWith(prefix)) {
            return null;
        }
        String key = url.substring(prefix.length());
        return KEY.matcher(key).matches() ? key : null;
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    /** The MIME type of the signature found at the start of the file, or {@code null} when it is none of the allowed ones. */
    static String detectType(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String extension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> "webp";
        };
    }

    /** Only echoed back in the response, never used as a path: strips any directory part and limits the length. */
    private static String displayName(String original) {
        if (original == null) {
            return null;
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
