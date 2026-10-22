package pl.pollub.pari.spot.web;

import pl.pollub.pari.auth.security.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
import pl.pollub.pari.availability.domain.AvailabilityException;
import pl.pollub.pari.availability.domain.AvailabilityRule;
import pl.pollub.pari.availability.service.AvailabilityService;
import pl.pollub.pari.common.domain.TimeRange;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Owner's weekly schedule and blocked periods of a space (F03). Identity is temporary, see {@link SpotController}. */
@RestController
@RequestMapping("/api/spots/{spotId}")
public class AvailabilityController {

    public record RuleRequest(
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    public record RuleResponse(UUID id, int dayOfWeek, LocalTime startTime, LocalTime endTime,
                               LocalDate validFrom, LocalDate validTo) {

        static RuleResponse of(AvailabilityRule r) {
            return new RuleResponse(r.getId(), r.getDayOfWeek(), r.getStartTime(), r.getEndTime(),
                    r.getValidFrom(), r.getValidTo());
        }
    }

    public record BlockRequest(@NotNull Instant start, @NotNull Instant end, String reason) {
    }

    public record BlockedPeriodResponse(UUID id, Instant start, Instant end, String reason) {

        static BlockedPeriodResponse of(AvailabilityException e) {
            return new BlockedPeriodResponse(e.getId(), e.getPeriod().start(), e.getPeriod().end(), e.getReason());
        }
    }

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/availability-rules")
    public List<RuleResponse> rules(@PathVariable UUID spotId) {
        return availability.weeklyRules(spotId).stream().map(RuleResponse::of).toList();
    }

    /** Replaces the whole weekly schedule. An empty list means the space is open all the time. */
    @PutMapping("/availability-rules")
    public List<RuleResponse> replaceRules(@CurrentUserId UUID userId,
                                           @PathVariable UUID spotId,
                                           @Valid @RequestBody List<@Valid RuleRequest> rules) {
        return availability.replaceWeeklyRules(userId, spotId, rules.stream()
                        .map(r -> new AvailabilityService.RuleCommand(r.dayOfWeek(), r.startTime(), r.endTime(),
                                r.validFrom(), r.validTo()))
                        .toList())
                .stream().map(RuleResponse::of).toList();
    }

    @PostMapping("/blocked-periods")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockedPeriodResponse block(@CurrentUserId UUID userId,
                                       @PathVariable UUID spotId, @Valid @RequestBody BlockRequest r) {
        return BlockedPeriodResponse.of(availability.blockPeriod(userId, spotId, new TimeRange(r.start(), r.end()), r.reason()));
    }

    @DeleteMapping("/blocked-periods/{periodId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@CurrentUserId UUID userId, @PathVariable UUID spotId,
                        @PathVariable UUID periodId) {
        availability.unblockPeriod(userId, spotId, periodId);
    }
}
