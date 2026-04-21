package com.listener.app.client;

import com.listener.app.config.WhisperProperties;
import com.listener.app.exception.WhisperTranscriptionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Calls the OpenAI Whisper transcription API.
 *
 * <p>Sends audio bytes as multipart/form-data and returns the plain-text transcript.
 * Retries automatically on transient failures with exponential backoff.</p>
 */
@Slf4j
@Component
public class WhisperClient {

    private final WebClient webClient;
    private final WhisperProperties properties;

    public WhisperClient(WhisperProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(25 * 1024 * 1024))  // 25 MB buffer
                .build();
    }

    /**
     * Transcribes the given audio bytes using the Whisper API.
     * Retries up to {@code retry.max-attempts} times with exponential backoff.
     *
     * @param audioBytes       raw audio data (must be ≤25 MB)
     * @param originalFilename filename including extension (required by Whisper)
     * @return trimmed transcript text
     * @throws WhisperTranscriptionException if all retry attempts fail
     */
    @Retryable(
            retryFor = WhisperTranscriptionException.class,
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String transcribe(byte[] audioBytes, String originalFilename) {
        log.debug("Sending audio to Whisper API — file: {}, size: {} bytes",
                originalFilename, audioBytes.length);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();

        // Whisper requires the "file" part with a proper filename
        bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return originalFilename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        bodyBuilder.part("model", properties.getModel());
        bodyBuilder.part("response_format", "text");

        try {
            String transcript = webClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("Whisper transcription complete — {} characters",
                    transcript != null ? transcript.length() : 0);
            return transcript != null ? transcript.trim() : "";

        } catch (WebClientResponseException ex) {
            log.debug("Whisper API returned {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new WhisperTranscriptionException(
                    "Whisper API returned " + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            log.debug("Whisper API call failed: {}", ex.getMessage(), ex);
            throw new WhisperTranscriptionException(
                    "Whisper API call failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Recovery method invoked when all retry attempts are exhausted.
     * Re-throws so the caller (CaptureService) can route to DLQ.
     */
    @Recover
    public String recoverTranscribe(WhisperTranscriptionException ex,
                                    byte[] audioBytes, String originalFilename) {
        log.debug("Whisper retries exhausted — file: {}, size: {} bytes",
                originalFilename, audioBytes.length);
        throw ex;
    }
}
