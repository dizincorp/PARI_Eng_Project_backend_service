package pl.pollub.pari.spot.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import pl.pollub.pari.common.domain.BaseEntity;
import pl.pollub.pari.common.domain.IllegalStateTransitionException;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.common.security.EncryptedStringConverter;
import pl.pollub.pari.user.domain.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "parking_spots")
@Getter
public class ParkingSpot extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Setter
    @Column(nullable = false)
    private String name;

    @Setter
    @Column(columnDefinition = "text")
    private String description;

    @Setter
    @Embedded
    private Address address;

    @Setter
    @Embedded
    private GeoPoint location;

    @Setter
    @Embedded
    private Dimensions dimensions;

    @Setter
    @Column(name = "hourly_price_minor", nullable = false)
    private Money hourlyPrice;

    /** Price of a whole day (24 h) when the owner sets one; {@code null}: a daily booking is priced from the hourly price. */
    @Setter
    @Column(name = "daily_rate_minor")
    private Money dailyPrice;

    /** Price of one month when the owner sets one; {@code null}: a monthly booking is priced from the hourly price. */
    @Setter
    @Column(name = "monthly_rate_minor")
    private Money monthlyPrice;

    @Setter
    @Column(columnDefinition = "text")
    private String accessInstructions;

    /**
     * The gate PIN the owner configured for this space (PARI-85), encrypted at rest. It is shown to the owner only and is
     * copied into the reservation when the booking is confirmed. Not the reservation's own check-in {@code pinCode}.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "owner_gate_pin", columnDefinition = "text")
    private String ownerGatePin;

    @Setter
    private boolean autoAccept = true;

    /** F04: when on, check-in and check-out change {@link #getOccupancy()} and are pushed to clients. */
    @Setter
    private boolean realtimeStatusEnabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Occupancy occupancy = Occupancy.FREE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpotStatus status = SpotStatus.DRAFT;

    @Column(name = "is_verified", nullable = false)
    private boolean verified;

    private Instant ownershipDeclaredAt;

    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal avgRating = BigDecimal.ZERO;

    private int reviewCount;

    @OneToMany(mappedBy = "spot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<SpotPhoto> photos = new ArrayList<>();

    /** The bookable spaces of the listing, including removed ones (PARI-65); a new listing starts with #1. */
    @OneToMany(mappedBy = "spot", cascade = CascadeType.ALL)
    @OrderBy("ordinal ASC")
    private List<SpotUnit> units = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "spot_amenities",
            joinColumns = @JoinColumn(name = "spot_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    private Set<Amenity> amenities = new HashSet<>();

    protected ParkingSpot() {
    }

    public ParkingSpot(User owner, String name, String description, Address address, GeoPoint location,
                       Dimensions dimensions, Money hourlyPrice) {
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.address = address;
        this.location = location;
        this.dimensions = dimensions;
        this.hourlyPrice = hourlyPrice;
        this.units.add(new SpotUnit(this, "#1", 1));
    }

    /** The spaces that can still be booked, in creation order. */
    public List<SpotUnit> liveUnits() {
        return units.stream().filter(SpotUnit::isLive).toList();
    }

    /** The first live space: the one a booking gets when it does not name a unit. */
    public SpotUnit firstLiveUnit() {
        return units.stream().filter(SpotUnit::isLive).findFirst().orElse(null);
    }

    /**
     * Adds a space. Without a label it is called after its creation number (#2, #3, ...); the label must differ from the
     * labels of the live spaces.
     */
    public SpotUnit addUnit(String label) {
        int ordinal = units.stream().mapToInt(SpotUnit::getOrdinal).max().orElse(0) + 1;
        String wanted = label == null || label.isBlank() ? nextFreeLabel(ordinal) : SpotUnit.validLabel(label);
        requireFreeLabel(wanted, null);
        SpotUnit unit = new SpotUnit(this, wanted, ordinal);
        units.add(unit);
        return unit;
    }

    public void renameUnit(SpotUnit unit, String label) {
        String wanted = SpotUnit.validLabel(label);
        requireFreeLabel(wanted, unit);
        unit.rename(wanted);
    }

    /** Takes a space out of service; the last live space cannot be removed. */
    public void removeUnit(SpotUnit unit, Instant now) {
        if (!unit.isLive()) {
            throw new IllegalStateTransitionException("Unit", "REMOVED", "be removed");
        }
        if (liveUnits().size() <= 1) {
            throw new IllegalStateTransitionException("Unit", "LAST", "be removed");
        }
        unit.remove(now);
    }

    public boolean hasLiveUnitLabel(String label, SpotUnit except) {
        return units.stream().anyMatch(u -> u.isLive() && u != except && u.getLabel().equalsIgnoreCase(label.trim()));
    }

    private void requireFreeLabel(String label, SpotUnit except) {
        if (hasLiveUnitLabel(label, except)) {
            throw new IllegalArgumentException("A space with the label " + label + " already exists");
        }
    }

    private String nextFreeLabel(int from) {
        int n = from;
        while (hasLiveUnitLabel("#" + n, null)) {
            n++;
        }
        return "#" + n;
    }

    /** F09: the owner confirms the right to dispose of the space. Required before the listing can be activated. */
    public void declareOwnership(Instant now) {
        this.ownershipDeclaredAt = now;
    }

    public void activate() {
        if (status == SpotStatus.BLOCKED || status == SpotStatus.ARCHIVED) {
            throw new IllegalStateTransitionException("ParkingSpot", status, "be activated");
        }
        if (ownershipDeclaredAt == null) {
            throw new IllegalStateTransitionException("the owner must confirm the right to dispose of the space before activation");
        }
        this.status = SpotStatus.ACTIVE;
    }

    public void deactivate() {
        if (status != SpotStatus.ACTIVE) {
            throw new IllegalStateTransitionException("ParkingSpot", status, "be deactivated");
        }
        this.status = SpotStatus.INACTIVE;
    }

    /** The owner removes the space for good. It disappears from every list; reservations that already ended keep pointing at it. */
    public void archive() {
        if (status == SpotStatus.ARCHIVED) {
            return;
        }
        this.status = SpotStatus.ARCHIVED;
    }

    /** Moderation: hides the listing, for example when its owner is blocked. */
    public void block() {
        if (status == SpotStatus.ARCHIVED) {
            return;
        }
        this.status = SpotStatus.BLOCKED;
    }

    public void unblock() {
        if (status == SpotStatus.BLOCKED) {
            this.status = SpotStatus.INACTIVE;
        }
    }

    public void markVerified() {
        this.verified = true;
    }

    public void revokeVerification() {
        this.verified = false;
    }

    /** True when the listing can receive reservations. Availability rules are checked separately. */
    public boolean isBookable() {
        return status == SpotStatus.ACTIVE;
    }

    public void occupy() {
        this.occupancy = Occupancy.OCCUPIED;
    }

    public void release() {
        this.occupancy = Occupancy.FREE;
    }

    public void updateRating(BigDecimal average, int count) {
        this.avgRating = average;
        this.reviewCount = count;
    }

    public SpotPhoto addPhoto(String storageKey) {
        SpotPhoto photo = new SpotPhoto(this, storageKey, photos.size());
        photos.add(photo);
        return photo;
    }

    /**
     * Puts a new photo in front as the cover and detaches the previous cover; the other photos keep their order.
     * Returns the detached photo (null when the space had none) so the caller can delete its file.
     */
    public SpotPhoto replaceCoverPhoto(String storageKey) {
        SpotPhoto old = photos.isEmpty() ? null : photos.get(0);
        if (old != null) {
            photos.remove(old);
        }
        photos.add(0, new SpotPhoto(this, storageKey, 0));
        renumberPhotos();
        return old;
    }

    /** Sets the order of the photos; the ids must be exactly the ids of the current photos, each once. */
    public void reorderPhotos(List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.size() != photos.size()
                || new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new IllegalArgumentException("The list must contain every photo of the space exactly once");
        }
        List<SpotPhoto> ordered = new ArrayList<>();
        for (UUID id : orderedIds) {
            ordered.add(photos.stream().filter(p -> id.equals(p.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown photo " + id)));
        }
        photos.clear();
        photos.addAll(ordered);
        renumberPhotos();
    }

    private void renumberPhotos() {
        for (int i = 0; i < photos.size(); i++) {
            photos.get(i).setPosition(i);
        }
    }

    public void removePhoto(SpotPhoto photo) {
        photos.remove(photo);
        renumberPhotos();
    }

    /** Sets, changes or clears the owner's gate PIN; a null or blank value removes it. */
    public void changeOwnerGatePin(String pin) {
        this.ownerGatePin = pin == null || pin.isBlank() ? null : pin.trim();
    }
}
