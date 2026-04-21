package com.listener.app.exception;

/**
 * Thrown when the server cannot service a request due to internal contention
 * (e.g. lock timeout on a GitHub file path). Mapped to HTTP 503 by
 * {@link GlobalExceptionHandler}.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
