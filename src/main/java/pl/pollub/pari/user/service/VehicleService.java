package pl.pollub.pari.user.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;
import pl.pollub.pari.user.repository.UserRepository;
import pl.pollub.pari.user.repository.VehicleRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A driver's own vehicles. Every operation is limited to vehicles of the calling user. */
@Service
public class VehicleService {

    public record VehicleView(UUID id, String model, String plateNumber, Instant createdAt) {

        static VehicleView of(Vehicle v) {
            return new VehicleView(v.getId(), v.getModel(), v.getPlateNumber(), v.getCreatedAt());
        }
    }

    private final VehicleRepository vehicles;
    private final UserRepository users;
    private final ReservationRepository reservations;

    public VehicleService(VehicleRepository vehicles, UserRepository users, ReservationRepository reservations) {
        this.vehicles = vehicles;
        this.users = users;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public List<VehicleView> list(UUID userId) {
        return vehicles.findByUserIdOrderByCreatedAtAsc(userId).stream().map(VehicleView::of).toList();
    }

    @Transactional
    public VehicleView create(UUID userId, String model, String plateNumber) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        Vehicle vehicle = new Vehicle(user, model.trim(), plateNumber);
        if (vehicles.findByUserIdAndPlateNumber(userId, vehicle.getPlateNumber()).isPresent()) {
            throw new ConflictException("VEHICLE_ALREADY_EXISTS", "You already registered a vehicle with this plate number");
        }
        return VehicleView.of(vehicles.saveAndFlush(vehicle));
    }

    /**
     * Deletes a vehicle. Refused while it is part of a reservation that is not finished (PENDING_*, CONFIRMED, IN_PROGRESS)
     * and also when finished reservations still reference it, because the history must keep its vehicle.
     */
    @Transactional
    public void delete(UUID userId, UUID vehicleId) {
        Vehicle vehicle = vehicles.findByIdAndUserId(vehicleId, userId)
                .orElseThrow(() -> new NotFoundException("Vehicle not found"));
        if (reservations.existsByVehicleIdAndStatusIn(vehicleId, ReservationStatus.SLOT_HOLDING)) {
            throw new ConflictException("VEHICLE_IN_ACTIVE_RESERVATION", "The vehicle is used in an active reservation");
        }
        if (reservations.existsByVehicleId(vehicleId)) {
            throw new ConflictException("VEHICLE_HAS_RESERVATION_HISTORY", "The vehicle is part of past reservations and cannot be deleted");
        }
        vehicles.delete(vehicle);
    }
}
