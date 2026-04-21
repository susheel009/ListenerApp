package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code dlq.*} properties from application.yml.
 * Controls Dead Letter Queue storage and retry scheduling.
 */
@Data
@ConfigurationProperties(prefix = "dlq")
public class DlqProperties {

    /** Base directory for DLQ storage (contains whisper-unreachable/ and github-unreachable/). */
    private String basePath = "./dlq";

    /** Interval in milliseconds between DLQ retry scans. */
    private long retryIntervalMs = 300_000;  // 5 minutes

    /** Max retry attempts before an item is moved to the failed/ subfolder. */
    private int maxRetryAttempts = 5;
}
