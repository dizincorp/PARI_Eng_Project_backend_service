package pl.pollub.pari.spot.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.spot.service.SpotSearchService;
import pl.pollub.pari.spot.service.SpotService;
import pl.pollub.pari.spot.service.SpotView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Parking spaces: creation and management by the owner, and public search.
 *
 * <p>TEMPORARY identity: the caller is taken from the {@code X-User-Id} header until Spring Security with JWT is added.
 * Anyone can send any id, so this must not be exposed outside development.
 */
@RestController
@RequestMapping("/api/spots")
public class SpotController {

    public static final String USER_HEADER = "X-User-Id";

    public record CreateSpotRequest(
            @NotBlank String name,
            String description,
            @NotBlank String street,
            @NotBlank String city,
            @NotBlank String postalCode,
            String country,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @Positive int lengthCm,
            @Positive int widthCm,
            @Positive Integer heightCm,
            @NotNull @DecimalMin("0.01") BigDecimal hourlyPrice,
            String accessInstructions,
            Boolean autoAccept,
            Set<String> amenities,
            List<String> photoKeys,
            boolean ownershipDeclared,
            boolean requestVerification) {
    }

    private final SpotService spotService;
    private final SpotSearchService searchService;

    public SpotController(SpotService spotService, SpotSearchService searchService) {
        this.spotService = spotService;
        this.searchService = searchService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpotView create(@RequestHeader(USER_HEADER) UUID userId, @Valid @RequestBody CreateSpotRequest r) {
        return spotService.create(userId, new SpotService.CreateSpotCommand(r.name(), r.description(), r.street(), r.city(),
                r.postalCode(), r.country(), r.lat(), r.lng(), r.lengthCm(), r.widthCm(), r.heightCm(),
                toMoney(r.hourlyPrice()), r.accessInstructions(), r.autoAccept() == null || r.autoAccept(),
                r.amenities(), r.photoKeys(), r.ownershipDeclared(), r.requestVerification()));
    }

    /** Map search. Public; the optional header only lets an owner see their own access instructions. */
    @GetMapping
    public List<SpotSearchService.SearchHit> search(
            @RequestHeader(value = USER_HEADER, required = false) UUID userId,
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5000") double radiusMeters,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean onlyVerified,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return searchService.search(new SpotSearchService.SearchCriteria(lat, lng, radiusMeters,
                maxPrice == null ? null : toMoney(maxPrice), onlyVerified, start, end, page, size), userId);
    }

    @GetMapping("/mine")
    public List<SpotView> mine(@RequestHeader(USER_HEADER) UUID userId) {
        return spotService.listOwn(userId);
    }

    @GetMapping("/{id}")
    public SpotView get(@RequestHeader(value = USER_HEADER, required = false) UUID userId, @PathVariable UUID id) {
        return spotService.get(id, userId);
    }

    @PostMapping("/{id}/activate")
    public SpotView activate(@RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id) {
        return spotService.activate(userId, id);
    }

    @PostMapping("/{id}/deactivate")
    public SpotView deactivate(@RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id) {
        return spotService.deactivate(userId, id);
    }

    static Money toMoney(BigDecimal decimalPln) {
        return Money.pln(decimalPln.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }
}
