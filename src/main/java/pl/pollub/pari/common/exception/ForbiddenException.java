package pl.pollub.pari.common.exception;

/** The caller is known but not allowed to do this. The web layer maps it to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
