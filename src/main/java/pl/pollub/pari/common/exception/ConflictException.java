package pl.pollub.pari.common.exception;

import lombok.Getter;

/** A business rule prevents the operation (slot taken, spot not bookable, ...). The web layer maps it to HTTP 409. */
@Getter
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
