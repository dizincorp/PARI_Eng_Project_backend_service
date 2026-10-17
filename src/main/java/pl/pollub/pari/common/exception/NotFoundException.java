package pl.pollub.pari.common.exception;

/** The requested entity does not exist (or is not visible to the caller). The web layer maps it to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
