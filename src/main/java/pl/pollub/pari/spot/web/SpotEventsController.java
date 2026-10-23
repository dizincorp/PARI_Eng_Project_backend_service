package pl.pollub.pari.spot.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.pollub.pari.spot.service.SpotOccupancyStream;

/** Live occupancy of parking spaces for the map (PARI-21). Public, like the spot search. */
@RestController
@RequestMapping("/api/spots")
public class SpotEventsController {

    private final SpotOccupancyStream stream;

    public SpotEventsController(SpotOccupancyStream stream) {
        this.stream = stream;
    }

    /** Event {@code occupancy} with data {@code {spotId, occupancy: FREE|OCCUPIED, at}}. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return stream.subscribe();
    }
}
