package pl.pollub.pari.support.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.pollub.pari.common.exception.ConflictException;
import pl.pollub.pari.common.exception.ForbiddenException;
import pl.pollub.pari.common.exception.NotFoundException;
import pl.pollub.pari.common.repository.AuditLogRepository;
import pl.pollub.pari.payment.repository.PaymentRepository;
import pl.pollub.pari.common.service.AuditTrail;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.reservation.domain.ReservationStatus;
import pl.pollub.pari.reservation.repository.ReservationRepository;
import pl.pollub.pari.support.domain.Report;
import pl.pollub.pari.support.domain.ReportMessage;
import pl.pollub.pari.support.domain.ReportStatus;
import pl.pollub.pari.support.event.TicketReplyEvent;
import pl.pollub.pari.support.domain.TicketPriority;
import pl.pollub.pari.support.domain.TicketSource;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.event.TicketEvent;
import pl.pollub.pari.support.repository.ReportRepository;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Support tickets (PARI-70): the owner or the driver of a reservation opens one, the system opens a VEHICLE_NOT_LEFT
 * ticket for a session that is still running after the reserved end, and support works them from one shared queue.
 * Nothing closes a ticket automatically.
 */
@Service
public class SupportTicketService {

    private final ReportRepository tickets;
    private final ReservationRepository reservations;
    private final UserRepository users;
    private final AuditTrail audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditLogRepository auditLog;
    private final PaymentRepository payments;
    private final ZoneId businessZone;

    public SupportTicketService(ReportRepository tickets, ReservationRepository reservations, UserRepository users,
                                AuditTrail audit, Clock clock, ApplicationEventPublisher events, AuditLogRepository auditLog,
                                PaymentRepository payments, ZoneId businessZone) {
        this.auditLog = auditLog;
        this.payments = payments;
        this.businessZone = businessZone;
        this.tickets = tickets;
        this.reservations = reservations;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    /**
     * The owner reports "vehicle still on my spot" (from the reserved end, while the session runs or after it was completed;
     * not for upcoming, cancelled, rejected, expired or no-show reservations), the driver "I can't
     * leave" (while the session runs), or either reports OTHER at any time. One open VEHICLE_NOT_LEFT ticket per reservation.
     */
    @Transactional
    public TicketView create(UUID userId, UUID reservationId, TicketType type, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A description is required");
        }
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        boolean isOwner = reservation.getSpot().getOwner().getId().equals(userId);
        boolean isDriver = reservation.getDriver().getId().equals(userId);
        if (!isOwner && !isDriver) {
            throw new NotFoundException("Reservation not found");
        }
        Instant now = clock.instant();
        TicketSource source = isOwner ? TicketSource.OWNER : TicketSource.DRIVER;
        switch (type) {
            case VEHICLE_NOT_LEFT -> {
                if (!isOwner) {
                    throw new ForbiddenException("Only the owner can report a vehicle that did not leave");
                }
                if (now.isBefore(reservation.getPeriod().end())) {
                    throw new ConflictException("TICKET_TOO_EARLY", "This ticket can be opened from the end of the reservation",
                            Map.of("availableAt", reservation.getPeriod().end()));
                }
                // IN_PROGRESS: the vehicle is still checked in. COMPLETED: the session was ended (manually or by the
                // automatic check-out) but the vehicle may still stand there; the ticket is then only a support record (PARI-93).
                if (reservation.getStatus() != ReservationStatus.IN_PROGRESS
                        && reservation.getStatus() != ReservationStatus.COMPLETED) {
                    throw new ConflictException("SESSION_NOT_RUNNING", "The parking session is not running");
                }
                if (hasOpen(reservationId, type)) {
                    throw new ConflictException("TICKET_ALREADY_OPEN", "A ticket about this vehicle is already open");
                }
            }
            case CANNOT_LEAVE -> {
                if (!isDriver) {
                    throw new ForbiddenException("Only the driver can report that they cannot leave");
                }
                if (reservation.getStatus() != ReservationStatus.IN_PROGRESS) {
                    throw new ConflictException("SESSION_NOT_RUNNING", "The parking session is not running");
                }
            }
            case OTHER -> {
                // any participant, at any time
            }
        }
        User reported = isOwner ? reservation.getDriver() : reservation.getSpot().getOwner();
        Report ticket = tickets.save(Report.ticket(reservation, user, reported, type, source, description));
        audit.record(userId, "Ticket", ticket.getId(), "TICKET_CREATED",
                Map.of("type", type.name(), "source", source.name(), "code", reservation.getCode()));
        events.publishEvent(TicketEvent.of(TicketEvent.Type.CREATED, ticket, null));
        return TicketView.of(ticket, false);
    }

    /**
     * Opens the SYSTEM ticket of a session still running after the reserved end. Does nothing when a VEHICLE_NOT_LEFT
     * ticket exists for the reservation, open or closed, so a closed ticket is not reopened every minute.
     */
    @Transactional
    public boolean createSystemTicket(UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.IN_PROGRESS
                || tickets.existsByReservationIdAndType(reservationId, TicketType.VEHICLE_NOT_LEFT)) {
            return false;
        }
        Report ticket = tickets.save(Report.ticket(reservation, null, reservation.getDriver(), TicketType.VEHICLE_NOT_LEFT,
                TicketSource.SYSTEM, "The vehicle is still on the spot after the end of the reservation."));
        audit.record(null, "Ticket", ticket.getId(), "TICKET_CREATED",
                Map.of("type", ticket.getType().name(), "source", "SYSTEM", "code", reservation.getCode()));
        events.publishEvent(TicketEvent.of(TicketEvent.Type.CREATED, ticket, null));
        return true;
    }

    /** Tickets of the reservations the user takes part in, newest first. */
    @Transactional(readOnly = true)
    public List<TicketView> mine(UUID userId, int page, int size) {
        return tickets.findInvolving(userId, PageRequest.of(page, size)).map(t -> TicketView.of(t, false)).getContent();
    }

    /** One ticket for support, or for a participant of the reservation (internal notes hidden). */
    @Transactional(readOnly = true)
    public TicketView get(UUID userId, UUID ticketId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        Report ticket = load(ticketId);
        boolean support = isSupport(user);
        if (!support && !involved(ticket, userId)) {
            throw new NotFoundException("Ticket not found");
        }
        return TicketView.of(ticket, support);
    }

    /** The shared queue: high priority first, then the oldest. Support only. */
    @Transactional(readOnly = true)
    public List<TicketView> queue(UUID supportId, ReportStatus status, TicketType type, TicketPriority priority,
                                  boolean onlyUnassigned, int page, int size) {
        requireSupport(supportId);
        return tickets.queue(status, type, priority, onlyUnassigned, PageRequest.of(page, size))
                .map(t -> TicketView.of(t, true)).getContent();
    }

    /**
     * The reservation of a ticket for the support page (PARI-81): spot, booking interval and time zone, vehicle, driver and
     * owner with their account ids and contact data, lifecycle, amounts, payments and refund state. Never includes the PIN or
     * the QR token. SUPPORT or ADMIN only; 404 when the ticket does not exist.
     */
    @Transactional(readOnly = true)
    public SupportReservationView reservationOf(UUID supportId, UUID ticketId) {
        requireSupport(supportId);
        Report ticket = load(ticketId);
        Reservation reservation = ticket.getReservation();
        return SupportReservationView.of(reservation, payments.findByReservationIdOrderByCreatedAtAsc(reservation.getId()),
                businessZone.getId());
    }

    /** The audit trail of a ticket, oldest first and paginated (PARI-84). Support only. */
    @Transactional(readOnly = true)
    public AuditEventView.Page auditTrail(UUID supportId, UUID ticketId, int page, int size) {
        requireSupport(supportId);
        load(ticketId);
        var result = auditLog.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("Ticket", ticketId, PageRequest.of(page, size));
        java.util.Set<UUID> actorIds = result.getContent().stream().map(e -> e.getActorId())
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> names = new java.util.HashMap<>();
        users.findAllById(actorIds).forEach(u -> names.put(u.getId(), u.getFirstName() + " " + u.getLastName()));
        List<AuditEventView> items = result.getContent().stream()
                .map(e -> AuditEventView.of(e, e.getActorId() == null ? null : names.get(e.getActorId()))).toList();
        return new AuditEventView.Page(items, page, size, result.getTotalElements());
    }

    /** Active support agents and administrators who can take a ticket (PARI-83). Support only. */
    @Transactional(readOnly = true)
    public List<StaffView> staff(UUID supportId) {
        requireSupport(supportId);
        return users.findActiveStaff(Role.SUPPORT, Role.ADMIN).stream().map(StaffView::of).toList();
    }

    /** Support takes a ticket from the queue. */
    @Transactional
    public TicketView claim(UUID supportId, UUID ticketId) {
        User support = requireSupport(supportId);
        Report ticket = load(ticketId);
        if (ticket.getAssignedTo() != null && !ticket.getAssignedTo().getId().equals(supportId)) {
            throw new ConflictException("TICKET_ALREADY_CLAIMED", "Another support employee already took this ticket");
        }
        ticket.claim(support);
        audit.record(supportId, "Ticket", ticket.getId(), "TICKET_CLAIMED",
                Map.of("assignedToId", supportId.toString(), "ticketStatus", ticket.getStatus().name()));
        return TicketView.of(ticket, true);
    }

    /** A message in the thread. Participants write public messages; support may also leave an internal note. */
    @Transactional
    public TicketView addMessage(UUID userId, UUID ticketId, String body, boolean internalNote) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A message cannot be empty");
        }
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        Report ticket = load(ticketId);
        boolean support = isSupport(user);
        if (!support && !involved(ticket, userId)) {
            throw new NotFoundException("Ticket not found");
        }
        if (internalNote && !support) {
            throw new ForbiddenException("Only support can leave internal notes");
        }
        ReportMessage message = ticket.addMessage(user, body, internalNote);
        User reporter = ticket.getReporter();
        if (support && !internalNote && reporter != null && !reporter.getId().equals(userId)) {
            tickets.flush(); // the message gets its id when it is persisted
            var reservation = ticket.getReservation();
            events.publishEvent(new TicketReplyEvent(ticket.getId(), message.getId(), reporter.getId(), userId,
                    reservation.getId(), reservation.getCode(), reservation.getSpot().getId(), reservation.getSpot().getName()));
        }
        return TicketView.of(ticket, support);
    }

    Report load(UUID ticketId) {
        return tickets.findById(ticketId).orElseThrow(() -> new NotFoundException("Ticket not found"));
    }

    User requireSupport(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        if (!isSupport(user)) {
            throw new ForbiddenException("The SUPPORT role is required");
        }
        return user;
    }

    private boolean hasOpen(UUID reservationId, TicketType type) {
        return tickets.existsByReservationIdAndTypeAndStatusIn(reservationId, type, ReportStatus.OPEN_STATUSES);
    }

    private static boolean isSupport(User user) {
        return user.hasRole(Role.SUPPORT) || user.hasRole(Role.ADMIN);
    }

    private static boolean involved(Report ticket, UUID userId) {
        return (ticket.getReporter() != null && ticket.getReporter().getId().equals(userId))
                || ticket.getReservation().getDriver().getId().equals(userId)
                || ticket.getReservation().getSpot().getOwner().getId().equals(userId);
    }
}
