package com.listener.app.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code github.*} properties from application.yml.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /** GitHub personal access token (needs repo / contents:write scope). */
    @NotBlank(message = "GitHub token is required")
    private String token;

    /** Repository owner (GitHub username or org). */
    @NotBlank(message = "GitHub owner is required")
    private String owner;

    /** Repository name. */
    @NotBlank(message = "GitHub repo is required")
    private String repo;

    /** Target branch for commits. */
    @NotBlank(message = "GitHub branch is required")
    private String branch;

    /** Directory inside the repo where daily markdown files are stored. */
    @NotBlank(message = "GitHub inbox path is required")
    private String inboxPath;
}
