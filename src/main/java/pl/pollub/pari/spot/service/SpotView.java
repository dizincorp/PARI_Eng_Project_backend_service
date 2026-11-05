package pl.pollub.pari.spot.service;

import pl.pollub.pari.spot.domain.Amenity;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.spot.domain.SpotPhoto;
import pl.pollub.pari.spot.domain.SpotStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read model of a parking space, built inside the service transaction so controllers never touch lazy entities.
 * {@code accessInstructions} is filled only for the owner of the space.
 */
public record SpotView(
        UUID id,
        UUID ownerId,
        String ownerFirstName,
        String ownerLastName,
        String ownerPhoneNumber,
        String ownerAvatarUrl,
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
        BigDecimal hourlyPrice,
        BigDecimal dailyPrice,
        BigDecimal monthlyPrice,
        String currency,
        String accessInstructions,
        boolean autoAccept,
        boolean realtimeStatusEnabled,
        Occupancy occupancy,
        SpotStatus status,
        boolean verified,
        BigDecimal avgRating,
        int reviewCount,
        List<String> amenities,
        List<String> photoKeys) {

    /** The view for a viewer who is not known to hold a reservation: the owner's phone number is shown to the owner only. */
    public static SpotView of(ParkingSpot spot, UUID viewerId) {
        return of(spot, viewerId, false);
    }

    /**
     * @param viewerHoldsReservation true when the viewer is a driver with a reservation on this space; together with the
     *                               owner it is the only audience that sees the owner's phone number
     */
    public static SpotView of(ParkingSpot spot, UUID viewerId, boolean viewerHoldsReservation) {
        boolean isOwner = spot.getOwner().getId().equals(viewerId);
        return new SpotView(
                spot.getId(),
                spot.getOwner().getId(),
                spot.getOwner().getFirstName(),
                spot.getOwner().getLastName(),
                isOwner || viewerHoldsReservation ? spot.getOwner().getPhone() : null,
                spot.getOwner().getAvatarUrl(),
                spot.getName(),
                spot.getDescription(),
                spot.getAddress().street(),
                spot.getAddress().city(),
                spot.getAddress().postalCode(),
                spot.getAddress().country(),
                spot.getLocation().lat(),
                spot.getLocation().lng(),
                spot.getDimensions().lengthCm(),
                spot.getDimensions().widthCm(),
                spot.getDimensions().heightCm(),
                spot.getHourlyPrice().toDecimal(),
                spot.getDailyPrice() == null ? null : spot.getDailyPrice().toDecimal(),
                spot.getMonthlyPrice() == null ? null : spot.getMonthlyPrice().toDecimal(),
                spot.getHourlyPrice().currency(),
                isOwner ? spot.getAccessInstructions() : null,
                spot.isAutoAccept(),
                spot.isRealtimeStatusEnabled(),
                spot.getOccupancy(),
                spot.getStatus(),
                spot.isVerified(),
                spot.getAvgRating(),
                spot.getReviewCount(),
                spot.getAmenities().stream().map(Amenity::getCode).sorted().toList(),
                spot.getPhotos().stream().map(SpotPhoto::getStorageKey).toList());
    }
}
