package com.listener.app.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

/**
 * Serializes the request body for GitHub Contents API PUT.
 * {@code sha} is null when creating a new file and excluded from JSON in that case.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitHubPutRequest {

    /** Commit message. */
    String message;

    /** Base64-encoded full file content. */
    String content;

    /** Blob SHA of the file being updated (null for new files). */
    String sha;

    /** Target branch. */
    String branch;
}
