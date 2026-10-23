package pl.pollub.pari.media.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.media.service.MediaStorage.StoredMedia;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceTest {

    private final MediaStorage storage = mock(MediaStorage.class);
    private final SpotService spots = mock(SpotService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MediaService service = new MediaService(storage, spots, users);
    private final MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1});
    private final UUID userId = UUID.randomUUID();
    private final UUID spotId = UUID.randomUUID();

    private static StoredMedia stored(String key) {
        return new StoredMedia(UUID.randomUUID(), key, "http://x/media/" + key, "a.jpg", "image/jpeg", 1);
    }

    @Test
    void aFileIsNotWrittenWhenTheCallerMayNotManageTheSpot() {
        doThrow(new ForbiddenException("no")).when(spots).requireManageable(userId, spotId);

        assertThatThrownBy(() -> service.addSpotPhoto(userId, spotId, file)).isInstanceOf(ForbiddenException.class);
        verify(storage, never()).store(any(), any());
    }

    @Test
    void theFileIsRemovedAgainWhenAttachingItFails() {
        when(storage.store(MediaCategory.SPOT_PHOTO, file)).thenReturn(stored("spots/k.jpg"));
        when(spots.addPhoto(any(), any(), anyString(), anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.addSpotPhoto(userId, spotId, file)).isInstanceOf(IllegalStateException.class);
        verify(storage).delete("spots/k.jpg");
    }

    @Test
    void deletingAPhotoRemovesItsFile() {
        UUID photoId = UUID.randomUUID();
        when(spots.removePhoto(userId, spotId, photoId)).thenReturn("spots/k.jpg");

        service.deleteSpotPhoto(userId, spotId, photoId);

        verify(storage).delete("spots/k.jpg");
    }

    @Test
    void newAvatarReplacesTheOldOneAndDeletesTheOldFile() {
        User user = new User("a@example.com", "hash", "A", "B", null, Set.of(Role.DRIVER));
        user.setAvatarUrl("http://x/media/avatars/old.png");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(storage.store(MediaCategory.AVATAR, file)).thenReturn(stored("avatars/new.jpg"));
        when(storage.keyOfUrl("http://x/media/avatars/old.png")).thenReturn("avatars/old.png");

        StoredMedia result = service.updateAvatar(userId, file);

        assertThat(user.getAvatarUrl()).isEqualTo(result.url()).endsWith("avatars/new.jpg");
        verify(storage).delete("avatars/old.png");
    }
}
