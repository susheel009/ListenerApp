package com.listener.app.exception;

/**
 * Thrown when the uploaded audio file has an unsupported extension.
 * Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class UnsupportedAudioFormatException extends RuntimeException {

    public UnsupportedAudioFormatException(String message) {
        super(message);
    }
}
