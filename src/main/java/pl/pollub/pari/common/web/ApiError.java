package pl.pollub.pari.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Single error body for every failed request, as the frontend expects (errorCode, message, statusCode, timestamp).
 * {@code details} carries machine-readable facts of some business errors (for example {@code availableAt}); it is
 * left out of the JSON when empty.
 */
public record ApiError(String errorCode, String message, int statusCode, Instant timestamp,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details) {

    public ApiError(String errorCode, String message, int statusCode, Instant timestamp) {
        this(errorCode, message, statusCode, timestamp, null);
    }
}
