package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code retry.*} properties from application.yml.
 * Shared by all {@code @Retryable} clients.
 */
@Data
@ConfigurationProperties(prefix = "retry")
public class RetryProperties {

    /** Maximum number of attempts (including the first call). */
    private int maxAttempts = 3;

    /** Initial delay in milliseconds before the first retry. */
    private long initialDelayMs = 1000;

    /** Multiplier for exponential backoff (delay × multiplier each retry). */
    private double multiplier = 2.0;
}
