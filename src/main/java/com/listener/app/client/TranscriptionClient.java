package com.listener.app.client;

import com.listener.app.config.TranscriptionProperties;
import com.listener.app.exception.NonRetryableUpstreamException;
import com.listener.app.exception.TranscriptionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.file.Path;

/**
 * Calls the external transcription API.
 * Uses RestClient with Apache HttpClient 5 connection pooling.
 * Retries automatically on transient failures with exponential backoff.
 */
@Slf4j
@Component
public class TranscriptionClient {

    private final RestClient restClient;
    private final TranscriptionProperties properties;

    public TranscriptionClient(TranscriptionProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(10);
        connManager.setDefaultMaxPerRoute(5);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(10_000);
        factory.setConnectionRequestTimeout(300_000); // Transcription can take minutes

        this.restClient = restClientBuilder
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();
    }

    /**
     * Transcribes audio from a file path — zero heap copy.
     * Uses {@link FileSystemResource} to stream the file directly into the multipart body.
     */
    @Retryable(
            retryFor = TranscriptionException.class,
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String transcribe(Path audioFile, String originalFilename) {
        log.debug("Sending audio to Transcription API — file: {}, path: {}",
                originalFilename, audioFile);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();

        FileSystemResource resource = new FileSystemResource(audioFile) {
            @Override
            public String getFilename() {
                return originalFilename;
            }
        };

        bodyBuilder.part("file", resource)
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        bodyBuilder.part("model", properties.getModel());
        bodyBuilder.part("response_format", "text");

        return executeTranscription(bodyBuilder, originalFilename);
    }

    /**
     * Transcribes audio from a byte array — used by DLQ retries where we
     * already have the bytes in memory from a previously stored file.
     */
    @Retryable(
            retryFor = TranscriptionException.class,
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

        bodyBuilder.part("file", new org.springframework.core.io.ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return originalFilename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        bodyBuilder.part("model", properties.getModel());
        bodyBuilder.part("response_format", "text");

        return executeTranscription(bodyBuilder, originalFilename);
    }

    @Recover
    public String recoverTranscribePath(TranscriptionException ex,
                                        Path audioFile, String originalFilename) {
        log.debug("Transcription retries exhausted — file: {}", originalFilename);
        throw ex;
    }

    @Recover
    public String recoverTranscribeBytes(TranscriptionException ex,
                                         byte[] audioBytes, String originalFilename) {
        log.debug("Transcription retries exhausted — file: {}, size: {} bytes",
                originalFilename, audioBytes.length);
        throw ex;
    }

    private String executeTranscription(MultipartBodyBuilder bodyBuilder, String filename) {
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
                throw new NonRetryableUpstreamException(
                        "Non-retryable Transcription Error: " + ex.getStatusCode(), ex);
            }
            throw new TranscriptionException(
                    "Transcription API call failed with " + ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            // Network / timeout errors — retryable
            log.debug("Transcription API call failed: {}", ex.getMessage());
            throw new TranscriptionException(
                    "Transcription API call failed: " + ex.getMessage(), ex);
        }
    }
}
