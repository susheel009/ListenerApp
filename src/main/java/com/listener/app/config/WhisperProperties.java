package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code whisper.*} properties from application.yml.
 */
@Data
@ConfigurationProperties(prefix = "whisper")
public class WhisperProperties {

    /** OpenAI API key for Whisper. */
    private String apiKey;

    /** Whisper transcription endpoint URL. */
    private String url;

    /** Whisper model identifier (e.g. "whisper-1"). */
    private String model;
}
