package pl.pollub.pari.common.exception;

import lombok.Getter;

import java.util.Map;

/**
 * A business rule prevents the operation (slot taken, spot not bookable, ...). The web layer maps it to HTTP 409.
 * {@code details} are optional machine-readable facts returned to the client next to the error code.
 */
@Getter
public class ConflictException extends RuntimeException {

    private final String errorCode;
    private final Map<String, Object> details;

    public ConflictException(String errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public ConflictException(String errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
