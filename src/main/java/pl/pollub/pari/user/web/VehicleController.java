package pl.pollub.pari.user.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.spot.web.SpotController;
import pl.pollub.pari.user.service.VehicleService;

import java.util.List;
import java.util.UUID;

/** The caller's vehicles. Identity is temporary, see {@link SpotController}. */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    /** The plate is normalised to upper case without spaces before it is stored, for example "wa 98765" becomes WA98765. */
    public record CreateVehicleRequest(
            @NotBlank @Size(max = 100) String model,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9 -]{2,15}$", message = "must be 2-15 letters, digits, spaces or dashes") String plateNumber) {
    }

    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    @GetMapping
    public List<VehicleService.VehicleView> list(@RequestHeader(SpotController.USER_HEADER) UUID userId) {
        return vehicles.list(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleService.VehicleView create(@RequestHeader(SpotController.USER_HEADER) UUID userId,
                                             @Valid @RequestBody CreateVehicleRequest r) {
        return vehicles.create(userId, r.model(), r.plateNumber());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(SpotController.USER_HEADER) UUID userId, @PathVariable UUID id) {
        vehicles.delete(userId, id);
    }
}
