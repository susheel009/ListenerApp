package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code capture.*} properties from application.yml.
 * Controls concurrency limits for the capture endpoint.
 */
@Data
@ConfigurationProperties(prefix = "capture")
public class CaptureProperties {

    /** Maximum number of concurrent capture requests to prevent disk/heap exhaustion. */
    private int maxConcurrent = 5;
}
