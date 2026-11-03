package pl.pollub.pari.spot.web;

import pl.pollub.pari.auth.security.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.common.domain.Money;
import pl.pollub.pari.spot.service.SpotCalendarService;
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
 * <p>The caller is the user of the Bearer token ({@link CurrentUserId}). The {@code X-User-Id} header is honoured only
 * when {@code pari.security.jwt.dev-header-auth} is on (development and tests).
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
            @DecimalMin("0.01") BigDecimal dailyPrice,
            @DecimalMin("0.01") BigDecimal monthlyPrice,
            String accessInstructions,
            Boolean autoAccept,
            Set<String> amenities,
            List<String> photoKeys,
            boolean ownershipDeclared,
            boolean requestVerification) {
    }

    /** The prices are replaced as a whole: an absent {@code dailyPrice} or {@code monthlyPrice} removes the one that was set. */
    public record UpdateSpotRequest(
            @NotBlank String name,
            String description,
            @NotNull @DecimalMin("0.01") BigDecimal hourlyPrice,
            @DecimalMin("0.01") BigDecimal dailyPrice,
            @DecimalMin("0.01") BigDecimal monthlyPrice,
            @Positive int lengthCm,
            @Positive int widthCm,
            @Positive Integer heightCm,
            String accessInstructions,
            Set<String> amenities) {
    }

    private final SpotService spotService;
    private final SpotSearchService searchService;
    private final SpotCalendarService calendar;

    public SpotController(SpotService spotService, SpotSearchService searchService, SpotCalendarService calendar) {
        this.spotService = spotService;
        this.searchService = searchService;
        this.calendar = calendar;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpotView create(@CurrentUserId UUID userId, @Valid @RequestBody CreateSpotRequest r) {
        return spotService.create(userId, new SpotService.CreateSpotCommand(r.name(), r.description(), r.street(), r.city(),
                r.postalCode(), r.country(), r.lat(), r.lng(), r.lengthCm(), r.widthCm(), r.heightCm(),
                toMoney(r.hourlyPrice()), toMoneyOrNull(r.dailyPrice()), toMoneyOrNull(r.monthlyPrice()),
                r.accessInstructions(), r.autoAccept() == null || r.autoAccept(),
                r.amenities(), r.photoKeys(), r.ownershipDeclared(), r.requestVerification()));
    }

    /** Map search. Public; the optional header only lets an owner see their own access instructions. */
    @GetMapping
    public List<SpotSearchService.SearchHit> search(
            @CurrentUserId UUID userId,
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
    public List<SpotView> mine(@CurrentUserId UUID userId) {
        return spotService.listOwn(userId);
    }

    @GetMapping("/{id}")
    public SpotView get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return spotService.get(id, userId);
    }

    @PutMapping("/{id}")
    public SpotView update(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody UpdateSpotRequest r) {
        return spotService.update(userId, id, new SpotService.UpdateSpotCommand(r.name(), r.description(),
                toMoney(r.hourlyPrice()), toMoneyOrNull(r.dailyPrice()), toMoneyOrNull(r.monthlyPrice()),
                r.lengthCm(), r.widthCm(), r.heightCm(), r.accessInstructions(), r.amenities()));
    }

    /** Removes the space (archived). 409 while it has active or upcoming reservations. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId UUID userId, @PathVariable UUID id) {
        spotService.archive(userId, id);
    }

    /** Occupied intervals (reservations and blocked periods) overlapping [from, to). */
    @GetMapping("/{id}/booked-slots")
    public List<SpotCalendarService.BookedSlot> bookedSlots(@CurrentUserId UUID userId, @PathVariable UUID id,
                                                            @RequestParam Instant from, @RequestParam Instant to) {
        return calendar.bookedSlots(userId, id, from, to);
    }

    @PostMapping("/{id}/activate")
    public SpotView activate(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return spotService.activate(userId, id);
    }

    @PostMapping("/{id}/deactivate")
    public SpotView deactivate(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return spotService.deactivate(userId, id);
    }

    static Money toMoney(BigDecimal decimalPln) {
        return Money.pln(decimalPln.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    static Money toMoneyOrNull(BigDecimal decimalPln) {
        return decimalPln == null ? null : toMoney(decimalPln);
    }
}
