package com.listener.app.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Response body for {@code POST /capture}.
 * Immutable — built once and returned as JSON.
 */
@Value
@Builder
public class CaptureResponse {

    String status;
    String timestamp;
    String transcript;
    String file;
    String commitSha;
}
