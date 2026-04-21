package com.listener.app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Deserializes the GitHub Contents API PUT response.
 * We only need the nested commit SHA.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubPutResponse {

    private CommitInfo commit;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommitInfo {
        private String sha;
    }
}
