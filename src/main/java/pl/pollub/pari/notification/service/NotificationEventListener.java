package pl.pollub.pari.notification.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.notification.repository.NotificationRepository;
import pl.pollub.pari.reservation.event.ReservationEvent;
import pl.pollub.pari.support.event.TicketEvent;
import pl.pollub.pari.support.event.TicketReplyEvent;
import pl.pollub.pari.user.repository.UserRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Turns reservation events into in-app notifications (PARI-39). Runs in the publisher's transaction, so a notification
 * exists exactly when the status change is committed. Texts are Polish, like the example in the ticket. NOT done yet:
 * the 15-minute start and expiry reminders.
 */
@Component
public class NotificationEventListener {

    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationEventListener(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @EventListener
    public void on(ReservationEvent e) {
        String place = "\"" + e.spotName() + "\"" + (e.multiUnit() ? " (" + e.unitLabel() + ")" : "");
        switch (e.type()) {
            case BOOKED -> {
                confirmed(e, place);
                notify(e, e.ownerId(), "NEW_RESERVATION", "Nowa rezerwacja",
                        "Miejsce " + place + " zostało zarezerwowane (" + e.code() + ").");
            }
            case ACCEPTED -> confirmed(e, place);
            case REQUESTED -> notify(e, e.ownerId(), "NEW_RESERVATION_REQUEST", "Nowa prośba o rezerwację",
                    "Prośba o rezerwację miejsca " + place + " (" + e.code() + ") czeka na Twoją decyzję.");
            case REJECTED -> notify(e, e.driverId(), "RESERVATION_REJECTED", "Rezerwacja odrzucona",
                    "Właściciel odrzucił rezerwację " + e.code() + ". Płatność zostanie zwrócona w całości.");
            case CANCELLED -> {
                UUID other = e.driverId().equals(e.actorId()) ? e.ownerId() : e.driverId();
                notify(e, other, "RESERVATION_CANCELLED", "Rezerwacja anulowana",
                        "Rezerwacja " + e.code() + " miejsca " + place + " została anulowana.");
            }
            case EXPIRED -> {
                notify(e, e.driverId(), "RESERVATION_EXPIRED", "Rezerwacja wygasła",
                        "Właściciel nie potwierdził rezerwacji " + e.code() + " na czas. Płatność została zwrócona w całości.");
                notify(e, e.ownerId(), "RESERVATION_EXPIRED", "Prośba o rezerwację wygasła",
                        "Prośba o rezerwację " + e.code() + " miejsca " + place + " wygasła bez Twojej decyzji.");
            }
            case CHECKED_IN -> notify(e, e.ownerId(), "DRIVER_CHECKED_IN", "Kierowca przyjechał",
                    "Kierowca rozpoczął postój na miejscu " + place + " (" + e.code() + ").");
            case AUTO_CHECKOUT -> {
                String fee = e.penalty() == null || e.penalty().signum() == 0
                        ? "" : " Naliczono dopłatę " + e.penalty().toPlainString() + " PLN.";
                notify(e, e.driverId(), "SESSION_AUTO_ENDED", "Postój zakończony automatycznie",
                        "Rezerwacja " + e.code() + " miejsca " + place + " dobiegła końca, a postój nie został zakończony. "
                                + "Zakończyliśmy go za Ciebie (" + e.overstayMinutes() + " min po planowanym końcu)." + fee);
                notify(e, e.ownerId(), "SESSION_AUTO_ENDED", "Postój zakończony automatycznie",
                        "Kierowca nie zakończył postoju na miejscu " + place + " (" + e.code() + "), system zakończył go po "
                                + e.overstayMinutes() + " min od planowanego końca." + fee);
            }
            case NO_SHOW -> {
                notify(e, e.driverId(), "RESERVATION_NO_SHOW", "Rezerwacja niewykorzystana",
                        "Nie rozpoczęto postoju w ramach rezerwacji " + e.code() + " miejsca " + place
                                + ". Rezerwacja została zamknięta, zwrot nie przysługuje.");
                notify(e, e.ownerId(), "RESERVATION_NO_SHOW", "Kierowca nie przyjechał",
                        "Kierowca nie rozpoczął postoju na miejscu " + place + " (" + e.code() + "). Otrzymasz zwykłą wypłatę.");
            }
            case REMINDER_BEFORE_END -> notify(e, e.driverId(), "SESSION_ENDING", "Postój kończy się za chwilę",
                    "Rezerwacja " + e.code() + " miejsca " + place + " kończy się za kilka minut. Zakończ postój w aplikacji.");
            case REMINDER_AT_END -> notify(e, e.driverId(), "SESSION_ENDED", "Czas rezerwacji minął",
                    "Rezerwacja " + e.code() + " miejsca " + place + " dobiegła końca. Zakończ postój i opuść miejsce. "
                            + "Po krótkim czasie naliczymy dopłatę.");
            case REMINDER_FINAL -> notify(e, e.driverId(), "SESSION_FINAL_REMINDER", "Ostatnie przypomnienie",
                    "Postój na miejscu " + place + " (" + e.code() + ") jest nadal otwarty. Wkrótce zakończymy go automatycznie. "
                            + "Jeśli nie możesz wyjechać, zgłoś to w aplikacji.");
            case OVERSTAY -> notify(e, e.ownerId(), "OVERSTAY_ALERT", "Przekroczenie czasu postoju",
                    "Kierowca opuścił miejsce " + place + " (" + e.code() + ") " + e.overstayMinutes()
                            + " min po planowanym końcu. Naliczono dopłatę " + e.penalty().toPlainString() + " PLN.");
        }
    }

    /** Ticket and evacuation notices (PARI-73). Support has no push: it works from the queue. */
    @EventListener
    public void on(TicketEvent e) {
        String place = "\"" + e.spotName() + "\"" + (e.multiUnit() ? " (" + e.unitLabel() + ")" : "");
        Map<String, Object> data = new java.util.HashMap<>(Map.of("reservationId", e.reservationId().toString(),
                "code", e.reservationCode(), "spotId", e.spotId().toString(), "ticketId", e.ticketId().toString()));
        if (e.unitLabel() != null) {
            data.put("unitLabel", e.unitLabel());
        }
        switch (e.type()) {
            case CREATED -> notifications.save(new Notification(users.getReferenceById(e.ownerId()), "TICKET_CREATED",
                    "Zgłoszenie dla rezerwacji " + e.reservationCode(),
                    "Dla rezerwacji " + e.reservationCode() + " miejsca " + place + " utworzono zgłoszenie do wsparcia.",
                    data, NotificationChannel.IN_APP));
            case EVACUATION_REQUESTED -> {
                String body = "Pojazd z miejsca " + place + " (" + e.reservationCode() + ") zostanie przeniesiony: " + e.detail() + ".";
                notifications.save(new Notification(users.getReferenceById(e.driverId()), "EVACUATION", "Ewakuacja pojazdu",
                        body, data, NotificationChannel.IN_APP));
                notifications.save(new Notification(users.getReferenceById(e.ownerId()), "EVACUATION", "Ewakuacja pojazdu",
                        body, data, NotificationChannel.IN_APP));
            }
            case PENALTY_REFUNDED -> {
                data.put("amount", e.detail());
                notifications.save(new Notification(users.getReferenceById(e.driverId()), "PENALTY_REFUNDED", "Zwrot kary",
                        "Zwróciliśmy karę " + e.detail() + " PLN naliczoną przez wsparcie dla rezerwacji " + e.reservationCode()
                                + " miejsca " + place + ".", data, NotificationChannel.IN_APP));
            }
            case OVERSTAY_PENALTY_REFUNDED -> {
                data.put("amount", e.detail());
                notifications.save(new Notification(users.getReferenceById(e.driverId()), "OVERSTAY_PENALTY_REFUNDED",
                        "Zwrot dopłaty za przekroczenie czasu",
                        "Zwróciliśmy dopłatę " + e.detail() + " PLN za przekroczenie czasu postoju na miejscu " + place
                                + " (" + e.reservationCode() + ").", data, NotificationChannel.IN_APP));
            }
            case EVACUATION_UPDATED -> {
                String body = "Status ewakuacji pojazdu z miejsca " + place + " (" + e.reservationCode() + "): " + e.detail() + ".";
                notifications.save(new Notification(users.getReferenceById(e.driverId()), "EVACUATION", "Ewakuacja pojazdu",
                        body, data, NotificationChannel.IN_APP));
                notifications.save(new Notification(users.getReferenceById(e.ownerId()), "EVACUATION", "Ewakuacja pojazdu",
                        body, data, NotificationChannel.IN_APP));
            }
        }
    }

    /**
     * A public support reply for the ticket's reporter (PARI-86). One notification per recipient and reply: a repeated
     * delivery of the same event creates nothing (backed by a unique index on user and message).
     */
    @EventListener
    public void on(TicketReplyEvent e) {
        if (notifications.existsByUserIdAndMessageId(e.reporterId(), e.messageId())) {
            return;
        }
        Map<String, Object> data = Map.of("reservationId", e.reservationId().toString(), "code", e.reservationCode(),
                "spotId", e.spotId().toString(), "ticketId", e.ticketId().toString(), "messageId", e.messageId().toString());
        notifications.save(new Notification(users.getReferenceById(e.reporterId()), "TICKET_REPLY", "Odpowiedź wsparcia",
                "Wsparcie odpowiedziało na Twoje zgłoszenie dla rezerwacji " + e.reservationCode() + " miejsca \""
                        + e.spotName() + "\".", data, NotificationChannel.IN_APP).aboutTicket(e.ticketId(), e.messageId()));
    }

    private void confirmed(ReservationEvent e, String place) {
        notify(e, e.driverId(), "RESERVATION_CONFIRMED", "Rezerwacja potwierdzona",
                "Twój kod PIN do bramy: " + e.pinCode() + ", miejsce " + place + " (" + e.code() + ").");
    }

    private void notify(ReservationEvent e, UUID userId, String type, String title, String body) {
        Map<String, Object> data = new java.util.HashMap<>(Map.of("reservationId", e.reservationId().toString(),
                "code", e.code(), "spotId", e.spotId().toString()));
        if (e.unitLabel() != null) {
            data.put("unitLabel", e.unitLabel());
        }
        notifications.save(new Notification(users.getReferenceById(userId), type, title, body, data, NotificationChannel.IN_APP));
    }
}
