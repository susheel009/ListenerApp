package com.listener.app.exception;

import lombok.Getter;

/**
 * Thrown when a GitHub API call fails.
 * Carries the transcript text so it is not lost in the error response.
 * Mapped to HTTP 502 by {@link GlobalExceptionHandler}.
 */
@Getter
public class GitHubApiException extends RuntimeException {

    /** The transcript that was successfully produced but could not be committed. */
    private final String transcript;

    public GitHubApiException(String message, String transcript) {
        super(message);
        this.transcript = transcript;
    }

    public GitHubApiException(String message, String transcript, Throwable cause) {
        super(message, cause);
        this.transcript = transcript;
    }
}
