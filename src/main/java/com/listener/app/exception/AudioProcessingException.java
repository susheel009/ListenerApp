package com.listener.app.exception;

/**
 * Thrown when audio file I/O or compression fails.
 * Mapped to HTTP 500 by {@link GlobalExceptionHandler}.
 */
public class AudioProcessingException extends RuntimeException {

    public AudioProcessingException(String message) {
        super(message);
    }

    public AudioProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
