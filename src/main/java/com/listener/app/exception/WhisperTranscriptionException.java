package com.listener.app.exception;

/**
 * Thrown when the OpenAI Whisper API call fails.
 * Mapped to HTTP 502 by {@link GlobalExceptionHandler}.
 */
public class WhisperTranscriptionException extends RuntimeException {

    public WhisperTranscriptionException(String message) {
        super(message);
    }

    public WhisperTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
