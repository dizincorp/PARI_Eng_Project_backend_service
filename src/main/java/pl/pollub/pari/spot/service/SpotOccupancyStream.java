package pl.pollub.pari.spot.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.pollub.pari.spot.event.SpotOccupancyChanged;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Server-Sent Events for spot markers (PARI-21): every connected client gets an {@code occupancy} event after the
 * check-in or check-out is committed. Subscribers are kept in memory, so with several backend instances each client only
 * sees changes made on its own instance (not solved yet).
 */
@Component
public class SpotOccupancyStream {

    /** The browser reconnects by itself after a timeout. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Set<SseEmitter> subscribers = new CopyOnWriteArraySet<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        register(emitter);
        return emitter;
    }

    void register(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
    }

    int subscriberCount() {
        return subscribers.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SpotOccupancyChanged change) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("occupancy").data(change));
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(emitter);
            }
        }
    }
}
