package pl.pollub.pari.spot.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.spot.domain.Address;
import pl.pollub.pari.spot.domain.Amenity;
import pl.pollub.pari.spot.domain.Dimensions;
import pl.pollub.pari.spot.domain.GeoPoint;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotPhoto;
import pl.pollub.pari.spot.domain.SpotStatus;
import pl.pollub.pari.spot.repository.AmenityRepository;
import pl.pollub.pari.spot.repository.ParkingSpotRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.verification.domain.VerificationRequest;
import pl.pollub.pari.verification.repository.VerificationRequestRepository;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creating and managing parking spaces (F01, F02, F09, F10). */
@Service
public class SpotService {

    /** Everything an owner enters in the "add space" form. Coordinates are required until address geocoding exists. */
    public record CreateSpotCommand(
            String name,
            String description,
            String street,
            String city,
            String postalCode,
            String country,
            double lat,
            double lng,
            int lengthCm,
            int widthCm,
            Integer heightCm,
            Money hourlyPrice,
            String accessInstructions,
            boolean autoAccept,
            Set<String> amenityCodes,
            List<String> photoKeys,
            boolean ownershipDeclared,
            boolean requestVerification) {
    }

    /** The fields an owner may change on an existing space. {@code amenityCodes == null} keeps the current amenities. */
    public record UpdateSpotCommand(
            String name,
            String description,
            Money hourlyPrice,
            int lengthCm,
            int widthCm,
            Integer heightCm,
            String accessInstructions,
            Set<String> amenityCodes) {
    }

    /** A photo attached to a space; {@code url} is the public address of the file. */
    public record PhotoView(UUID id, String storageKey, String url, int position) {
    }

    private final ParkingSpotRepository spots;
    private final AmenityRepository amenities;
    private final UserRepository users;
    private final VerificationRequestRepository verifications;
    private final ReservationRepository reservations;
    private final Clock clock;

    public SpotService(ParkingSpotRepository spots, AmenityRepository amenities, UserRepository users,
                       VerificationRequestRepository verifications, ReservationRepository reservations, Clock clock) {
        this.spots = spots;
        this.amenities = amenities;
        this.users = users;
        this.verifications = verifications;
        this.reservations = reservations;
        this.clock = clock;
    }

    /**
     * Creates and immediately activates a space. The owner must declare the right to dispose of it (F09); an inspection
     * request (F10) is queued when asked for. The listing becomes visible in search without waiting for the inspection.
     */
    @Transactional
    public SpotView create(UUID ownerId, CreateSpotCommand cmd) {
        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("User not found"));
        if (owner.isBlocked()) {
            throw new ForbiddenException("The account is blocked");
        }
        if (!owner.hasRole(Role.OWNER)) {
            throw new ForbiddenException("The OWNER role is required to list a parking space");
        }
        if (!cmd.ownershipDeclared()) {
            throw new IllegalArgumentException("The legal declaration of the right to the space is required (F09)");
        }
        if (cmd.hourlyPrice() == null || cmd.hourlyPrice().amountMinor() <= 0) {
            throw new IllegalArgumentException("The hourly price must be positive");
        }

        ParkingSpot spot = new ParkingSpot(owner, cmd.name().trim(), cmd.description(),
                new Address(cmd.street().trim(), cmd.city().trim(), cmd.postalCode().trim(),
                        cmd.country() == null || cmd.country().isBlank() ? "PL" : cmd.country().trim().toUpperCase()),
                new GeoPoint(cmd.lat(), cmd.lng()),
                new Dimensions(cmd.lengthCm(), cmd.widthCm(), cmd.heightCm()),
                cmd.hourlyPrice());
        spot.setAccessInstructions(cmd.accessInstructions());
        spot.setAutoAccept(cmd.autoAccept());
        spot.getAmenities().addAll(resolveAmenities(cmd.amenityCodes()));
        if (cmd.photoKeys() != null) {
            cmd.photoKeys().forEach(spot::addPhoto);
        }
        spot.declareOwnership(clock.instant());
        spot.activate();
        spots.save(spot);

        if (cmd.requestVerification()) {
            verifications.save(new VerificationRequest(spot, owner, clock.instant()));
        }
        return SpotView.of(spot, ownerId);
    }

    /** A space is public while it is active; any other status is visible to its owner only. */
    @Transactional(readOnly = true)
    public SpotView get(UUID spotId, UUID viewerId) {
        ParkingSpot spot = spots.findById(spotId).orElseThrow(() -> new NotFoundException("Parking spot not found"));
        boolean isOwner = spot.getOwner().getId().equals(viewerId);
        if (spot.getStatus() == SpotStatus.ARCHIVED || (spot.getStatus() != SpotStatus.ACTIVE && !isOwner)) {
            throw new NotFoundException("Parking spot not found");
        }
        return SpotView.of(spot, viewerId);
    }

    @Transactional(readOnly = true)
    public List<SpotView> listOwn(UUID ownerId) {
        return spots.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .filter(s -> s.getStatus() != SpotStatus.ARCHIVED)
                .map(s -> SpotView.of(s, ownerId))
                .toList();
    }

    /**
     * The owner (or an ADMIN) edits a space. Unknown space is 404; someone else's space is 403. The location, the owner and
     * the auto-accept setting are not changed here.
     */
    @Transactional
    public SpotView update(UUID userId, UUID spotId, UpdateSpotCommand cmd) {
        ParkingSpot spot = manageableSpot(userId, spotId);
        if (cmd.hourlyPrice() == null || cmd.hourlyPrice().amountMinor() <= 0) {
            throw new IllegalArgumentException("The hourly price must be positive");
        }
        spot.setName(cmd.name().trim());
        spot.setDescription(cmd.description());
        spot.setHourlyPrice(cmd.hourlyPrice());
        spot.setDimensions(new Dimensions(cmd.lengthCm(), cmd.widthCm(), cmd.heightCm()));
        spot.setAccessInstructions(cmd.accessInstructions());
        if (cmd.amenityCodes() != null) {
            Set<Amenity> resolved = resolveAmenities(cmd.amenityCodes());
            spot.getAmenities().clear();
            spot.getAmenities().addAll(resolved);
        }
        return SpotView.of(spot, userId);
    }

    /**
     * The owner (or an ADMIN) removes a space. Refused while a reservation still holds a slot (payment pending, awaiting
     * approval, confirmed or running). The space is archived, so finished reservations and payments keep their reference.
     */
    @Transactional
    public void archive(UUID userId, UUID spotId) {
        ParkingSpot spot = manageableSpot(userId, spotId);
        if (reservations.existsBySpotIdAndStatusIn(spotId, ReservationStatus.SLOT_HOLDING)) {
            throw new ConflictException("SPOT_HAS_ACTIVE_RESERVATIONS", "Cannot delete spot with active or upcoming reservations.");
        }
        spot.archive();
    }

    /** Fails with 404 or 403 unless the caller may manage the space; lets uploads be refused before a file is written. */
    @Transactional(readOnly = true)
    public void requireManageable(UUID userId, UUID spotId) {
        manageableSpot(userId, spotId);
    }

    /** Attaches an already stored image as the last photo of the space (owner or ADMIN). */
    @Transactional
    public PhotoView addPhoto(UUID userId, UUID spotId, String storageKey, String url) {
        ParkingSpot spot = manageableSpot(userId, spotId);
        SpotPhoto photo = spot.addPhoto(storageKey);
        spots.flush();
        return new PhotoView(photo.getId(), storageKey, url, photo.getPosition());
    }

    /** Detaches a photo (owner or ADMIN) and returns its storage key so the caller can delete the file. */
    @Transactional
    public String removePhoto(UUID userId, UUID spotId, UUID photoId) {
        ParkingSpot spot = manageableSpot(userId, spotId);
        SpotPhoto photo = spot.getPhotos().stream().filter(p -> photoId.equals(p.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Photo not found"));
        spot.removePhoto(photo);
        spots.flush();
        return photo.getStorageKey();
    }

    /** Puts a deactivated space back on the market. */
    @Transactional
    public SpotView activate(UUID ownerId, UUID spotId) {
        ParkingSpot spot = ownedSpot(ownerId, spotId);
        if (spot.getStatus() != SpotStatus.ACTIVE) {
            spot.activate();
        }
        return SpotView.of(spot, ownerId);
    }

    /** Hides the space from search and refuses new reservations; existing reservations stay valid. */
    @Transactional
    public SpotView deactivate(UUID ownerId, UUID spotId) {
        ParkingSpot spot = ownedSpot(ownerId, spotId);
        spot.deactivate();
        return SpotView.of(spot, ownerId);
    }

    /** The space for its owner or an ADMIN: 404 when it does not exist (or was removed), 403 for anyone else. */
    private ParkingSpot manageableSpot(UUID userId, UUID spotId) {
        ParkingSpot spot = spots.findById(spotId).orElseThrow(() -> new NotFoundException("Parking spot not found"));
        if (spot.getStatus() == SpotStatus.ARCHIVED) {
            throw new NotFoundException("Parking spot not found");
        }
        if (!spot.getOwner().getId().equals(userId)
                && !users.findById(userId).map(u -> u.hasRole(Role.ADMIN)).orElse(false)) {
            throw new ForbiddenException("Only the owner of the parking spot can do this");
        }
        return spot;
    }

    private ParkingSpot ownedSpot(UUID ownerId, UUID spotId) {
        return spots.findByIdAndOwnerId(spotId, ownerId)
                .filter(spot -> spot.getStatus() != SpotStatus.ARCHIVED)
                .orElseThrow(() -> new NotFoundException("Parking spot not found"));
    }

    private Set<Amenity> resolveAmenities(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        List<Amenity> found = amenities.findByCodeIn(codes);
        Set<String> foundCodes = new HashSet<>();
        found.forEach(a -> foundCodes.add(a.getCode()));
        for (String code : codes) {
            if (!foundCodes.contains(code)) {
                throw new IllegalArgumentException("Unknown amenity: " + code);
            }
        }
        return new HashSet<>(found);
    }
}
