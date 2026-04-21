package com.listener.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code audio.*} properties from application.yml.
 * Controls compression behaviour for oversized audio files.
 */
@Data
@ConfigurationProperties(prefix = "audio")
public class AudioProperties {

    /** Whisper API hard limit in bytes (25 MB). */
    private long whisperMaxBytes = 25_000_000;

    /** Minimum bitrate in kbps — quality floor for compression. */
    private int minBitrateKbps = 32;

    /** Maximum bitrate in kbps — no point going higher for voice. */
    private int maxBitrateKbps = 128;

    /** Path to the FFmpeg binary (or just "ffmpeg" if on system PATH). */
    private String ffmpegPath = "ffmpeg";
}
