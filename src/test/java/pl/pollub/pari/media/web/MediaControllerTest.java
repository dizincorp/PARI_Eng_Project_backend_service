package pl.pollub.pari.media.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.pollub.pari.auth.security.SecurityConfig;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.web.ApiExceptionHandler;
import pl.pollub.pari.media.service.FileTooLargeException;
import pl.pollub.pari.media.service.MediaCategory;
import pl.pollub.pari.media.service.MediaService;
import pl.pollub.pari.media.service.MediaStorage.StoredMedia;
import pl.pollub.pari.spot.service.SpotService.PhotoView;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class, MediaWebConfig.class, MediaControllerTest.Config.class})
class MediaControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "g.jpg", "image/jpeg", new byte[]{1});

    @Autowired
    MockMvc mvc;
    @MockitoBean
    MediaService media;

    private static StoredMedia stored() {
        return new StoredMedia(UUID.randomUUID(), "spots/k.jpg", "http://localhost:8080/media/spots/k.jpg", "g.jpg", "image/jpeg", 1);
    }

    @Test
    void uploadReturns201WithTheStoredFile() throws Exception {
        when(media.upload(eq(MediaCategory.SPOT_PHOTO), any())).thenReturn(stored());

        mvc.perform(multipart("/api/media/upload").file(FILE).param("category", "SPOT_PHOTO").header("X-User-Id", USER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").value("spots/k.jpg"))
                .andExpect(jsonPath("$.url").value("http://localhost:8080/media/spots/k.jpg"));
    }

    @Test
    void uploadNeedsAuthentication() throws Exception {
        mvc.perform(multipart("/api/media/upload").file(FILE).param("category", "AVATAR"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/spots/" + UUID.randomUUID() + "/photos").file(FILE)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/users/me/avatar").file(FILE)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/spots/" + UUID.randomUUID() + "/photos/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidFilesAreAnswered400() throws Exception {
        when(media.upload(any(), any())).thenThrow(new IllegalArgumentException("Only image/jpeg, image/png and image/webp files are accepted"));

        mvc.perform(multipart("/api/media/upload").file(FILE).param("category", "AVATAR").header("X-User-Id", USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void missingFileUnknownCategoryAreAnswered400() throws Exception {
        mvc.perform(multipart("/api/media/upload").param("category", "AVATAR").header("X-User-Id", USER))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/media/upload").file(FILE).param("category", "VIDEO").header("X-User-Id", USER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooLargeFilesAreAnswered413() throws Exception {
        when(media.upload(any(), any())).thenThrow(new FileTooLargeException("big"));

        mvc.perform(multipart("/api/media/upload").file(FILE).param("category", "AVATAR").header("X-User-Id", USER))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("FILE_TOO_LARGE"));
    }

    @Test
    void addingAPhotoReturnsItsIdAndRespectsOwnership() throws Exception {
        UUID spot = UUID.randomUUID();
        UUID photo = UUID.randomUUID();
        when(media.addSpotPhoto(eq(USER), eq(spot), any())).thenReturn(new PhotoView(photo, "spots/k.jpg", "http://x/media/spots/k.jpg", 2));

        mvc.perform(multipart("/api/spots/" + spot + "/photos").file(FILE).header("X-User-Id", USER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(photo.toString()))
                .andExpect(jsonPath("$.position").value(2));

        UUID stranger = UUID.randomUUID();
        when(media.addSpotPhoto(eq(stranger), eq(spot), any())).thenThrow(new ForbiddenException("no"));
        mvc.perform(multipart("/api/spots/" + spot + "/photos").file(FILE).header("X-User-Id", stranger))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingAPhotoReturns204() throws Exception {
        UUID spot = UUID.randomUUID();
        UUID photo = UUID.randomUUID();

        mvc.perform(delete("/api/spots/" + spot + "/photos/" + photo).header("X-User-Id", USER))
                .andExpect(status().isNoContent());
        verify(media).deleteSpotPhoto(USER, spot, photo);

        doThrow(new ForbiddenException("no")).when(media).deleteSpotPhoto(any(), any(), any());
        mvc.perform(delete("/api/spots/" + spot + "/photos/" + photo).header("X-User-Id", USER))
                .andExpect(status().isForbidden());
    }

    @Test
    void avatarUploadUsesTheCaller() throws Exception {
        when(media.updateAvatar(eq(USER), any())).thenReturn(stored());

        mvc.perform(multipart("/api/users/me/avatar").file(FILE).header("X-User-Id", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost:8080/media/spots/k.jpg"));
    }

    @Test
    void mediaFilesAreServedWithoutAuthentication() throws Exception {
        // anonymous access reaches the static handler (404 for a missing file) instead of being stopped with 401
        mvc.perform(get("/media/spots/missing.jpg")).andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class Config {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2029-12-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
