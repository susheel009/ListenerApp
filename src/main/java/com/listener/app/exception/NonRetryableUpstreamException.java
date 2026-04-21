package com.listener.app.exception;

/**
 * Thrown when an upstream API (Transcription or GitHub) returns a non-retryable
 * 4xx error (e.g. 401, 403, 422). Mapped to HTTP 502 by {@link GlobalExceptionHandler}.
 *
 * <p>Replaces the previous use of {@code IllegalArgumentException} which was
 * falling through to the generic 500 handler — misrepresenting legitimate
 * upstream rejections as internal server bugs.</p>
 */
public class NonRetryableUpstreamException extends RuntimeException {

    public NonRetryableUpstreamException(String message) {
        super(message);
    }

    public NonRetryableUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
