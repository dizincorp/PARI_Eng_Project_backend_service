package pl.pollub.pari.spot.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.pollub.pari.spot.domain.Occupancy;
import pl.pollub.pari.spot.event.SpotOccupancyChanged;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PARI-21: occupancy changes reach every subscriber; a broken connection is dropped. */
class SpotOccupancyStreamTest {

    private final SpotOccupancyStream stream = new SpotOccupancyStream();

    /** Records what was sent instead of writing to an HTTP response. */
    static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>();
        boolean broken;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("client gone");
            }
            sent.add(builder.build().stream().map(d -> String.valueOf(d.getData())).reduce("", String::concat));
        }
    }

    @Test
    void everySubscriberReceivesTheChange() {
        RecordingEmitter a = new RecordingEmitter();
        RecordingEmitter b = new RecordingEmitter();
        stream.register(a);
        stream.register(b);
        UUID spotId = UUID.randomUUID();

        stream.on(new SpotOccupancyChanged(spotId, Occupancy.OCCUPIED, Instant.parse("2030-01-07T10:00:00Z")));

        assertThat(a.sent).singleElement().asString().contains("occupancy").contains(spotId.toString()).contains("OCCUPIED");
        assertThat(b.sent).hasSize(1);
    }

    @Test
    void aBrokenConnectionIsDroppedAndTheOthersStillReceive() {
        RecordingEmitter gone = new RecordingEmitter();
        gone.broken = true;
        RecordingEmitter alive = new RecordingEmitter();
        stream.register(gone);
        stream.register(alive);

        stream.on(new SpotOccupancyChanged(UUID.randomUUID(), Occupancy.FREE, Instant.parse("2030-01-07T10:00:00Z")));

        assertThat(stream.subscriberCount()).isEqualTo(1);
        assertThat(alive.sent).hasSize(1);
    }
}
