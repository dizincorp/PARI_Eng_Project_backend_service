package pl.pollub.pari.media.service;

/** The uploaded file is bigger than the allowed maximum; answered with 413. */
public class FileTooLargeException extends RuntimeException {

    public FileTooLargeException(String message) {
        super(message);
    }
}
