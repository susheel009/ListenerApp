package com.listener.app.exception;

/**
 * Thrown when audio cannot be compressed below the Whisper 25 MB limit.
 * Mapped to HTTP 413 Payload Too Large by {@link GlobalExceptionHandler}.
 */
public class AudioTooLargeException extends RuntimeException {

    public AudioTooLargeException(String message) {
        super(message);
    }
}
