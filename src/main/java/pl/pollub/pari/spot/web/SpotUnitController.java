package pl.pollub.pari.spot.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.spot.domain.SpotUnit;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotUnitService;
import pl.pollub.pari.spot.service.SpotView;

import java.util.List;
import java.util.UUID;

/** The bookable spaces of a listing (PARI-65): public list, owner-only changes. */
@RestController
@RequestMapping("/api/spots/{id}/units")
public class SpotUnitController {

    private final SpotService spots;
    private final SpotUnitService units;

    public SpotUnitController(SpotService spots, SpotUnitService units) {
        this.spots = spots;
        this.units = units;
    }

    @GetMapping
    public List<SpotView.UnitRef> list(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return spots.get(id, userId).units();
    }

    /** A missing label gives the next free one (#2, #3, ...). Returns every live space of the listing. */
    public record AddUnitRequest(@Size(max = SpotUnit.MAX_LABEL_LENGTH) String label) {
    }

    public record RenameUnitRequest(@NotBlank @Size(max = SpotUnit.MAX_LABEL_LENGTH) String label) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<SpotView.UnitRef> add(@CurrentUserId UUID userId, @PathVariable UUID id,
                                      @Valid @RequestBody(required = false) AddUnitRequest request) {
        return units.add(userId, id, request == null ? null : request.label());
    }

    @PutMapping("/{unitId}")
    public List<SpotView.UnitRef> rename(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID unitId,
                                         @Valid @RequestBody RenameUnitRequest request) {
        return units.rename(userId, id, unitId, request.label());
    }

    @DeleteMapping("/{unitId}")
    public List<SpotView.UnitRef> remove(@CurrentUserId UUID userId, @PathVariable UUID id, @PathVariable UUID unitId) {
        return units.remove(userId, id, unitId);
    }
}
