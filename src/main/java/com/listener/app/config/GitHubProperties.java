package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code github.*} properties from application.yml.
 */
@Data
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {

    /** GitHub personal access token (needs repo / contents:write scope). */
    private String token;

    /** Repository owner (GitHub username or org). */
    private String owner;

    /** Repository name. */
    private String repo;

    /** Target branch for commits. */
    private String branch;

    /** Directory inside the repo where daily markdown files are stored. */
    private String inboxPath;
}
