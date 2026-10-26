package pl.pollub.pari.common.exception;

import lombok.Getter;

/** The request is well formed but its content is wrong (for example a wrong current password). The web layer maps it to HTTP 400. */
@Getter
public class BadRequestException extends RuntimeException {

    private final String errorCode;

    public BadRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
