package com.listener.app.exception;

/**
 * Thrown when the OpenAI Whisper API call fails.
 * Mapped to HTTP 502 by {@link GlobalExceptionHandler}.
 */
public class TranscriptionException extends RuntimeException {

    public TranscriptionException(String message) {
        super(message);
    }

    public TranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
