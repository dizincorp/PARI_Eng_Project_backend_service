package pl.pollub.pari.common.domain;

/** Thrown when an operation is not allowed in the current status of an entity. The web layer maps it to HTTP 409. */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }

    public IllegalStateTransitionException(String entity, Object from, String action) {
        super(entity + " in status " + from + " cannot " + action);
    }
}
