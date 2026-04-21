package com.listener.app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Deserializes the GitHub Contents API GET response.
 * Only the fields we need are mapped; everything else is ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubFileResponse {

    /** Base64-encoded file content (may contain line breaks). */
    private String content;

    /** Blob SHA — required for subsequent PUT to avoid conflicts. */
    private String sha;

    /** Encoding type (expected: "base64"). */
    private String encoding;
}
