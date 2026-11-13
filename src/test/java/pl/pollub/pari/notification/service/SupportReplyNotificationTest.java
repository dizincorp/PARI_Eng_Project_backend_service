package pl.pollub.pari.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import pl.pollub.pari.reservation.domain.Reservation;
import pl.pollub.pari.spot.domain.ParkingSpot;
import pl.pollub.pari.support.ServiceTestBase;
import pl.pollub.pari.support.domain.TicketType;
import pl.pollub.pari.support.event.TicketReplyEvent;
import pl.pollub.pari.support.service.ModerationService;
import pl.pollub.pari.support.service.SupportCommandService;
import pl.pollub.pari.support.service.SupportTicketService;
import pl.pollub.pari.support.service.TicketView;
import pl.pollub.pari.user.domain.Role;
import pl.pollub.pari.user.domain.User;
import pl.pollub.pari.user.domain.Vehicle;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PARI-86: a public support reply becomes one durable, ticket-linked notification for the reporter. */
@Import({SupportTicketService.class, SupportCommandService.class, ModerationService.class})
class SupportReplyNotificationTest extends ServiceTestBase {

    @Autowired
    SupportTicketService tickets;
    @Autowired
    NotificationService notifications;
    @Autowired
    ApplicationEventPublisher publisher;

    User owner;
    User driver;
    User support;
    ParkingSpot spot;
    TicketView ticket;

    @BeforeEach
    void setUp() {
        owner = persistUser(Role.OWNER);
        driver = persistUser(Role.DRIVER);
        support = persistUser(Role.SUPPORT);
        Vehicle vehicle = persistVehicle(driver);
        spot = persistSpot(owner, 51.2465, 22.5684, 1000);
        Reservation r = persistReservation(spot, driver, vehicle, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofMinutes(1)));
        r.markPaid(true);
        r.checkIn(NOW.minus(Duration.ofHours(3)).plusSeconds(60));
        em.flush();
        ticket = tickets.create(owner.getId(), r.getId(), TicketType.VEHICLE_NOT_LEFT, "Still there");
        em.flush();
    }

    @Test
    void aPublicReplyCreatesExactlyOneTicketLinkedNotificationForTheReporter() {
        tickets.addMessage(support.getId(), ticket.id(), "We are on it", false);
        em.flush();

        List<NotificationView> mine = replies(owner);
        assertThat(mine).singleElement().satisfies(n -> {
            assertThat(n.ticketId()).isEqualTo(ticket.id());
            assertThat(n.messageId()).isNotNull();
            assertThat(n.reservationId()).isNotNull();
            assertThat(n.read()).isFalse();
        });
        assertThat(replies(driver)).isEmpty();
    }

    @Test
    void anInternalNoteCreatesNoNotification() {
        tickets.addMessage(support.getId(), ticket.id(), "Calling the driver", true);
        em.flush();

        assertThat(replies(owner)).isEmpty();
    }

    @Test
    void theReportersOwnMessageAndAnotherPartysMessageDoNotNotifyTheAuthor() {
        tickets.addMessage(owner.getId(), ticket.id(), "Any news?", false);
        em.flush();

        assertThat(replies(owner)).isEmpty();
    }

    @Test
    void deliveringTheSameReplyEventAgainCreatesNothingNew() {
        tickets.addMessage(support.getId(), ticket.id(), "We are on it", false);
        em.flush();
        NotificationView first = replies(owner).get(0);

        publisher.publishEvent(new TicketReplyEvent(ticket.id(), first.messageId(), owner.getId(), support.getId(),
                first.reservationId(), "CODE", spot.getId(), spot.getName()));
        em.flush();

        assertThat(replies(owner)).hasSize(1);
    }

    @Test
    void readingATicketPersistsAndLeavesOtherTicketsAndUsersAlone() {
        tickets.addMessage(support.getId(), ticket.id(), "First", false);
        tickets.addMessage(support.getId(), ticket.id(), "Second", false);
        em.flush();
        UUID otherTicket = UUID.randomUUID();

        assertThat(notifications.markTicketRead(owner.getId(), otherTicket)).isZero();
        assertThat(notifications.markTicketRead(driver.getId(), ticket.id())).isZero();
        assertThat(notifications.markTicketRead(owner.getId(), ticket.id())).isEqualTo(2);
        em.clear();

        assertThat(replies(owner)).hasSize(2).allMatch(NotificationView::read);
        assertThat(notifications.list(owner.getId(), true, 0, 20)).noneMatch(n -> ticket.id().equals(n.ticketId()));
    }

    @Test
    void markingOneReplyReadDoesNotReadTheOther() {
        tickets.addMessage(support.getId(), ticket.id(), "First", false);
        tickets.addMessage(support.getId(), ticket.id(), "Second", false);
        em.flush();
        List<NotificationView> both = replies(owner);

        notifications.markRead(owner.getId(), both.get(0).id());
        em.flush();

        assertThat(replies(owner)).filteredOn(NotificationView::read).hasSize(1);
        assertThat(notifications.list(owner.getId(), true, 0, 20)).filteredOn(n -> n.ticketId() != null).hasSize(1);
    }

    private List<NotificationView> replies(User user) {
        return notifications.list(user.getId(), false, 0, 50).stream().filter(n -> "TICKET_REPLY".equals(n.type())).toList();
    }
}
