package com.listener.app.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code transcription.*} properties from application.yml.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "transcription")
public class TranscriptionProperties {

    @NotBlank(message = "API key is required")
    private String apiKey;

    @NotBlank(message = "URL is required")
    private String url;

    @NotBlank(message = "Model name is required")
    private String model;
}
