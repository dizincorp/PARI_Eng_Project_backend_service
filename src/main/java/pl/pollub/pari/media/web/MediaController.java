package pl.pollub.pari.media.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.media.service.MediaCategory;
import pl.pollub.pari.media.service.MediaService;
import pl.pollub.pari.media.service.MediaStorage.StoredMedia;
import pl.pollub.pari.spot.service.SpotService;

import java.util.UUID;

/** Image upload (PARI-40): generic upload, spot photos and the caller's avatar. Files are served from {@code /media/**}. */
@RestController
public class MediaController {

    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping(value = "/api/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public StoredMedia upload(@RequestParam MultipartFile file, @RequestParam MediaCategory category) {
        return media.upload(category, file);
    }

    @PostMapping(value = "/api/spots/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SpotService.PhotoView addPhoto(@CurrentUserId UUID userId, @PathVariable UUID id, @RequestParam MultipartFile file) {
        return media.addSpotPhoto(userId, id, file);
    }

    @DeleteMapping("/api/spots/{id}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID photoId) {
        media.deleteSpotPhoto(userId, id, photoId);
    }

    @PostMapping(value = "/api/users/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StoredMedia avatar(@CurrentUserId UUID userId, @RequestParam MultipartFile file) {
        return media.updateAvatar(userId, file);
    }
}
