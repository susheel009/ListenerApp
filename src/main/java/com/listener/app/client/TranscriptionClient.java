package com.listener.app.client;

import com.listener.app.config.TranscriptionProperties;
import com.listener.app.exception.TranscriptionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Calls the external transcription API.
 * Uses RestClient. Retries automatically on transient failures with backoff.
 */
@Slf4j
@Component
public class TranscriptionClient {

    private final RestClient restClient;
    private final TranscriptionProperties properties;

    public TranscriptionClient(TranscriptionProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(300));
        
        this.restClient = restClientBuilder
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    @Retryable(
            retryFor = TranscriptionException.class,
            noRetryFor = {IllegalArgumentException.class},
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String transcribe(byte[] audioBytes, String originalFilename) {
        log.debug("Sending audio to Transcription API — file: {}, size: {} bytes",
                originalFilename, audioBytes.length);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();

        bodyBuilder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return originalFilename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        bodyBuilder.part("model", properties.getModel());
        bodyBuilder.part("response_format", "text");

        try {
            String transcript = restClient.post()
                    .uri(properties.getUrl())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(bodyBuilder.build())
                    .retrieve()
                    .body(String.class);

            log.debug("Transcription complete — {} characters",
                    transcript != null ? transcript.length() : 0);
            return transcript != null ? transcript.trim() : "";

        } catch (RestClientResponseException ex) {
            log.debug("Transcription API returned {}", ex.getStatusCode());
            if (ex.getStatusCode().is4xxClientError() && ex.getStatusCode().value() != 429) {
                // Non-retryable
                throw new IllegalArgumentException("Non-retryable Transcription Error: " + ex.getStatusCode(), ex);
            }
            throw new TranscriptionException("Transcription API call failed with " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            log.debug("Transcription API call failed: {}", ex.getMessage());
            throw new TranscriptionException("Transcription API call failed: " + ex.getMessage(), ex);
        }
    }

    @Recover
    public String recoverTranscribe(TranscriptionException ex,
                                    byte[] audioBytes, String originalFilename) {
        log.debug("Transcription retries exhausted — file: {}, size: {} bytes",
                originalFilename, audioBytes.length);
        throw ex;
    }
}
