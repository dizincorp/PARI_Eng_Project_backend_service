package pl.pollub.pari.notification.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pl.pollub.pari.notification.domain.Notification;
import pl.pollub.pari.notification.domain.NotificationChannel;
import pl.pollub.pari.notification.repository.NotificationRepository;
import pl.pollub.pari.reservation.event.ReservationEvent;
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
        String place = "\"" + e.spotName() + "\"";
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
            case OVERSTAY -> notify(e, e.ownerId(), "OVERSTAY_ALERT", "Przekroczenie czasu postoju",
                    "Kierowca opuścił miejsce " + place + " (" + e.code() + ") " + e.overstayMinutes()
                            + " min po planowanym końcu. Naliczono dopłatę " + e.penalty().toPlainString() + " PLN.");
        }
    }

    private void confirmed(ReservationEvent e, String place) {
        notify(e, e.driverId(), "RESERVATION_CONFIRMED", "Rezerwacja potwierdzona",
                "Twój kod PIN do bramy: " + e.pinCode() + ", miejsce " + place + " (" + e.code() + ").");
    }

    private void notify(ReservationEvent e, UUID userId, String type, String title, String body) {
        notifications.save(new Notification(users.getReferenceById(userId), type, title, body,
                Map.of("reservationId", e.reservationId().toString(), "code", e.code(), "spotId", e.spotId().toString()),
                NotificationChannel.IN_APP));
    }
}
