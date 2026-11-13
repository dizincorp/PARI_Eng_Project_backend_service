package pl.pollub.pari.media.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.media.service.MediaStorage.StoredMedia;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotView;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/** Image uploads for spot photos and avatars. A file whose database update fails is removed again. */
@Service
public class MediaService {

    private final MediaStorage storage;
    private final SpotService spots;
    private final UserRepository users;

    public MediaService(MediaStorage storage, SpotService spots, UserRepository users) {
        this.storage = storage;
        this.spots = spots;
        this.users = users;
    }

    /** Stores a file without attaching it to anything; the caller uses the returned URL or key afterwards. */
    public StoredMedia upload(MediaCategory category, MultipartFile file) {
        return storage.store(category, file);
    }

    public SpotService.PhotoView addSpotPhoto(UUID userId, UUID spotId, MultipartFile file) {
        spots.requireManageable(userId, spotId);
        StoredMedia stored = storage.store(MediaCategory.SPOT_PHOTO, file);
        try {
            return spots.addPhoto(userId, spotId, stored.storageKey(), stored.url());
        } catch (RuntimeException e) {
            storage.delete(stored.storageKey());
            throw e;
        }
    }

    /** Replaces the cover photo: the new file becomes position 0 and the old cover's record and file are removed. */
    public SpotService.PhotoView replaceCoverPhoto(UUID userId, UUID spotId, MultipartFile file) {
        spots.requireManageable(userId, spotId);
        StoredMedia stored = storage.store(MediaCategory.SPOT_PHOTO, file);
        try {
            SpotService.CoverReplacement result = spots.replaceCoverPhoto(userId, spotId, stored.storageKey(), stored.url());
            storage.delete(result.replacedStorageKey());
            return result.photo();
        } catch (RuntimeException e) {
            storage.delete(stored.storageKey());
            throw e;
        }
    }

    /** The photos of a space with ids, positions and public URLs; visible to whoever may see the space. */
    public List<SpotService.PhotoView> listSpotPhotos(UUID viewerId, UUID spotId) {
        return spots.get(spotId, viewerId).photos().stream().map(this::view).toList();
    }

    public List<SpotService.PhotoView> reorderSpotPhotos(UUID userId, UUID spotId, List<UUID> orderedIds) {
        return spots.reorderPhotos(userId, spotId, orderedIds).stream().map(this::view).toList();
    }

    private SpotService.PhotoView view(SpotView.PhotoRef p) {
        return new SpotService.PhotoView(p.id(), p.storageKey(), storage.urlOf(p.storageKey()), p.position());
    }

    public void deleteSpotPhoto(UUID userId, UUID spotId, UUID photoId) {
        storage.delete(spots.removePhoto(userId, spotId, photoId));
    }

    /** Replaces the avatar of the caller and deletes the previous file when it was one of ours. */
    @Transactional
    public StoredMedia updateAvatar(UUID userId, MultipartFile file) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        StoredMedia stored = storage.store(MediaCategory.AVATAR, file);
        String oldKey = storage.keyOfUrl(user.getAvatarUrl());
        user.setAvatarUrl(stored.url());
        users.saveAndFlush(user);
        storage.delete(oldKey);
        return stored;
    }
}
