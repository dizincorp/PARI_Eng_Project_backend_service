package pl.pollub.pari.common.exception;

/** The credentials are wrong. The web layer maps it to HTTP 401. */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
