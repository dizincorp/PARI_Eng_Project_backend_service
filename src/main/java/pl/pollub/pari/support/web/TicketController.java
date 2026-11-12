package pl.pollub.pari.support.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.pollub.pari.auth.security.CurrentUserId;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.support.service.TicketView;

import java.util.List;
import java.util.UUID;

/** Tickets for drivers and owners (PARI-70): open one for a reservation, follow it, write in it. */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    /** OWNER may open VEHICLE_NOT_LEFT from the reserved end, DRIVER CANNOT_LEAVE during the session, both OTHER. */
    public record CreateTicketRequest(@NotNull UUID reservationId, @NotNull TicketType type, @NotBlank String description) {
    }

    public record MessageRequest(@NotBlank String body) {
    }

    private final SupportTicketService tickets;

    public TicketController(SupportTicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(@CurrentUserId UUID userId, @Valid @RequestBody CreateTicketRequest r) {
        return tickets.create(userId, r.reservationId(), r.type(), r.description());
    }

    /** Tickets of the reservations the caller takes part in. */
    @GetMapping
    public List<TicketView> mine(@CurrentUserId UUID userId, @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return tickets.mine(userId, page, Math.min(Math.max(size, 1), 50));
    }

    @GetMapping("/{id}")
    public TicketView get(@CurrentUserId UUID userId, @PathVariable UUID id) {
        return tickets.get(userId, id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView message(@CurrentUserId UUID userId, @PathVariable UUID id, @Valid @RequestBody MessageRequest r) {
        return tickets.addMessage(userId, id, r.body(), false);
    }
}
