package pl.pollub.pari.media.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import pl.pollub.pari.media.MediaProperties;
import pl.pollub.pari.media.service.MediaStorage.StoredMedia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStorageTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @TempDir
    Path dir;
    MediaStorage storage;

    @BeforeEach
    void setUp() {
        storage = new MediaStorage(new MediaProperties(dir.toString(), "https://pari.example/"));
    }

    @Test
    void storesAnImageUnderARandomNameAndReturnsItsPublicUrl() throws IOException {
        StoredMedia stored = storage.store(MediaCategory.SPOT_PHOTO, new MockMultipartFile("file", "my_garage.jpg", "image/jpeg", JPEG));

        assertThat(stored.storageKey()).matches("spots/[0-9a-f-]{36}\\.jpg").hasSizeLessThanOrEqualTo(500);
        assertThat(stored.url()).isEqualTo("https://pari.example/media/" + stored.storageKey());
        assertThat(stored.mimeType()).isEqualTo("image/jpeg");
        assertThat(stored.originalFilename()).isEqualTo("my_garage.jpg");
        assertThat(stored.sizeBytes()).isEqualTo(JPEG.length);
        assertThat(Files.readAllBytes(dir.resolve(stored.storageKey()))).isEqualTo(JPEG);
    }

    @Test
    void acceptsPngAndWebpAndPutsAvatarsInTheirOwnFolder() {
        assertThat(storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.png", "image/png", PNG)).storageKey())
                .startsWith("avatars/").endsWith(".png");
        assertThat(storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.webp", "image/webp", WEBP)).storageKey())
                .endsWith(".webp");
    }

    @Test
    void rejectsOtherDeclaredTypes() {
        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.gif", "image/gif", JPEG)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.pdf", "application/pdf", JPEG)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotTrustTheClientMimeType() {
        byte[] script = "<?php echo 1; ?>".getBytes();

        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "x.jpg", "image/jpeg", script)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "x.png", "image/png", JPEG)))
                .as("a JPEG announced as PNG").isInstanceOf(IllegalArgumentException.class);
        assertThat(listFiles()).isEmpty();
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] big = new byte[(int) MediaStorage.MAX_BYTES + 1];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        assertThatThrownBy(() -> storage.store(MediaCategory.AVATAR, new MockMultipartFile("file", "a.jpg", "image/jpeg", big)))
                .isInstanceOf(FileTooLargeException.class);
    }

    @Test
    void neverUsesTheClientFileNameInThePath() {
        StoredMedia stored = storage.store(MediaCategory.SPOT_PHOTO,
                new MockMultipartFile("file", "../../etc/passwd\\..\\x.jpg", "image/jpeg", JPEG));

        assertThat(stored.storageKey()).doesNotContain("..", "passwd");
        assertThat(stored.originalFilename()).isEqualTo("x.jpg");
        assertThat(listFiles()).hasSize(1).allMatch(p -> p.startsWith(dir.resolve("spots")));
    }

    @Test
    void deleteRemovesOurFilesAndIgnoresEverythingElse() throws IOException {
        StoredMedia stored = storage.store(MediaCategory.SPOT_PHOTO, new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG));
        Path outside = Files.createTempFile("outside", ".jpg");
        try {
            storage.delete("../" + outside.getFileName());
            storage.delete("https://elsewhere.example/a.jpg");
            storage.delete(null);
            assertThat(outside).exists();
            assertThat(dir.resolve(stored.storageKey())).exists();

            storage.delete(stored.storageKey());
            assertThat(dir.resolve(stored.storageKey())).doesNotExist();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void recognisesOnlyOwnUrlsAsStoredFiles() {
        String key = "avatars/c7a8e234-8b1e-45fd-912a-3e817492c10b.png";

        assertThat(storage.keyOfUrl("https://pari.example/media/" + key)).isEqualTo(key);
        assertThat(storage.keyOfUrl("https://other.example/media/" + key)).isNull();
        assertThat(storage.keyOfUrl("https://pari.example/media/../secret.png")).isNull();
        assertThat(storage.keyOfUrl(null)).isNull();
    }

    private java.util.List<Path> listFiles() {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
