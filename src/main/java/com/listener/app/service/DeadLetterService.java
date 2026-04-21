package com.listener.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.listener.app.client.GitHubClient;
import com.listener.app.client.TranscriptionClient;
import com.listener.app.config.DlqProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.DlqMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Dead Letter Queue for captures that failed due to external service unavailability.
 */
@Slf4j
@Component
public class DeadLetterService {

    private static final String TRANSCRIPTION_UNREACHABLE = "transcription-unreachable";
    private static final String GITHUB_UNREACHABLE = "github-unreachable";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")
            .withZone(ZoneId.of("UTC"));

    private final DlqProperties dlqProperties;
    private final GitHubProperties gitHubProperties;
    private final TranscriptionClient transcriptionClient;
    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public DeadLetterService(DlqProperties dlqProperties,
                             GitHubProperties gitHubProperties,
                             TranscriptionClient transcriptionClient,
                             GitHubClient gitHubClient,
                             ObjectMapper objectMapper) {
        this.dlqProperties = dlqProperties;
        this.gitHubProperties = gitHubProperties;
        this.transcriptionClient = transcriptionClient;
        this.gitHubClient = gitHubClient;
        this.objectMapper = objectMapper;
    }

    public void storeTranscriptionFailure(byte[] audioBytes, String filename, Instant timestamp,
                                    String error, boolean autoRetryable) {
        String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
        String timeStr = TIME_FMT.format(timestamp);
        String sanitized = sanitizeFilename(filename);
        String baseName = timeStr + "_" + sanitized;
        Path dlqDir = Path.of(dlqProperties.getBasePath(), TRANSCRIPTION_UNREACHABLE, dateStr);

        try {
            Files.createDirectories(dlqDir);

            Files.write(dlqDir.resolve(baseName), audioBytes);

            DlqMetadata metadata = DlqMetadata.builder()
                    .filename(filename)
                    .timestamp(timestamp.toString())
                    .error(error)
                    .retryCount(0)
                    .failureType(TRANSCRIPTION_UNREACHABLE)
                    .autoRetryable(autoRetryable)
                    .build();

            Files.writeString(dlqDir.resolve(baseName + ".meta.json"),
                    objectMapper.writeValueAsString(metadata));

            log.warn("DLQ stored (transcription-unreachable): {}/{} [autoRetry={}]",
                    dateStr, baseName, autoRetryable);

        } catch (IOException ex) {
            log.error("CRITICAL — failed to store in DLQ, audio data may be lost! " +
                      "File: {}, size: {} bytes", filename, audioBytes.length, ex);
        }
    }

    public void storeGitHubFailure(String transcript, String filename, Instant timestamp,
                                   String targetFilePath, String error) {
        String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
        String timeStr = TIME_FMT.format(timestamp);
        String sanitized = sanitizeFilename(filename);
        String baseName = timeStr + "_" + sanitized;
        Path dlqDir = Path.of(dlqProperties.getBasePath(), GITHUB_UNREACHABLE, dateStr);

        try {
            Files.createDirectories(dlqDir);

            DlqMetadata metadata = DlqMetadata.builder()
                    .filename(filename)
                    .timestamp(timestamp.toString())
                    .error(error)
                    .retryCount(0)
                    .failureType(GITHUB_UNREACHABLE)
                    .autoRetryable(true)
                    .transcript(transcript)
                    .targetFilePath(targetFilePath)
                    .build();

            Files.writeString(dlqDir.resolve(baseName + ".meta.json"),
                    objectMapper.writeValueAsString(metadata));

            log.warn("DLQ stored (github-unreachable): {}/{}", dateStr, baseName);

        } catch (IOException ex) {
            log.error("CRITICAL — failed to store in DLQ, transcript may be lost! " +
                      "Transcript size: {}", transcript.length(), ex);
        }
    }

    @Scheduled(
            fixedDelayString = "${dlq.retry-interval-ms:300000}",
            initialDelayString = "${dlq.retry-interval-ms:300000}"
    )
    public void retryFailedCaptures() {
        if (!isRunning.compareAndSet(false, true)) {
            log.debug("DLQ scheduler already running, skipping overlap.");
            return;
        }
        try {
            AtomicInteger globalFailures = new AtomicInteger(0);
            retryTranscriptionFailures(globalFailures);
            retryGitHubFailures(globalFailures);
        } finally {
            isRunning.set(false);
        }
    }

    private void retryTranscriptionFailures(AtomicInteger failures) {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), TRANSCRIPTION_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json")).sorted()) {

            metaFiles.forEach(meta -> {
                if (failures.get() >= 3) return; // Circuit breaker limit
                if (!retryTranscriptionItem(meta)) failures.incrementAndGet();
            });

        } catch (IOException ex) {
            log.error("Failed to scan DLQ directory: {}", dlqDir, ex);
        }
    }

    private boolean retryTranscriptionItem(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);

            if (!metadata.isAutoRetryable()) {
                return true;
            }
            if (metadata.getRetryCount() >= dlqProperties.getMaxRetryAttempts()) {
                log.error("DLQ item exceeded max retries ({}), permanently failed: {}",
                        dlqProperties.getMaxRetryAttempts(), metaPath);
                return true; // Don't count as standard transient failure
            }

            String metaFileName = metaPath.getFileName().toString();
            String audioFileName = metaFileName.replace(".meta.json", "");
            Path audioPath = metaPath.getParent().resolve(audioFileName);

            if (!Files.exists(audioPath)) {
                log.error("DLQ audio file missing — cannot retry: {}", audioPath);
                return true;
            }

            log.info("DLQ retry (transcription-unreachable) — attempt {}/{}: {}",
                    metadata.getRetryCount() + 1, dlqProperties.getMaxRetryAttempts(), audioFileName);

            byte[] audioBytes = Files.readAllBytes(audioPath);
            String transcript = transcriptionClient.transcribe(audioBytes, metadata.getFilename());

            Instant timestamp = Instant.parse(metadata.getTimestamp());
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, transcript);

            String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
            String filePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";
            String commitMessage = String.format("capture (dlq retry): voice note %s", isoTimestamp);

            gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);

            Files.deleteIfExists(audioPath);
            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(TRANSCRIPTION_UNREACHABLE));
            log.info("DLQ retry successful — transcription-unreachable item processed: {}", audioFileName);
            return true;
            
        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
            return false;
        }
    }

    private void retryGitHubFailures(AtomicInteger failures) {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), GITHUB_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json")).sorted()) {

            metaFiles.forEach(meta -> {
                if (failures.get() >= 3) return; // Circuit breaker limit
                if (!retryGitHubItem(meta)) failures.incrementAndGet();
            });

        } catch (IOException ex) {
            log.error("Failed to scan DLQ directory: {}", dlqDir, ex);
        }
    }

    private boolean retryGitHubItem(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);

            if (metadata.getRetryCount() >= dlqProperties.getMaxRetryAttempts()) {
                log.error("DLQ item exceeded max retries ({}), permanently failed: {}",
                        dlqProperties.getMaxRetryAttempts(), metaPath);
                return true; 
            }

            log.info("DLQ retry (github-unreachable) — attempt {}/{}: {}",
                    metadata.getRetryCount() + 1, dlqProperties.getMaxRetryAttempts(),
                    metaPath.getFileName());

            Instant timestamp = Instant.parse(metadata.getTimestamp());
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, metadata.getTranscript());

            String filePath = metadata.getTargetFilePath();
            String commitMessage = String.format("capture (dlq retry): voice note %s", isoTimestamp);

            gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);

            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(GITHUB_UNREACHABLE));
            log.info("DLQ retry successful — github-unreachable item processed: {}", metaPath.getFileName());
            return true;
            
        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
            return false;
        }
    }

    private DlqMetadata readMetadata(Path metaPath) throws IOException {
        return objectMapper.readValue(Files.readString(metaPath), DlqMetadata.class);
    }

    private void incrementRetryCount(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);
            metadata.setRetryCount(metadata.getRetryCount() + 1);
            
            Path tempMeta = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
            Files.writeString(tempMeta, objectMapper.writeValueAsString(metadata));
            Files.move(tempMeta, metaPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            
        } catch (IOException ex) {
            log.error("Failed to update retry count for DLQ item safely: {}", metaPath, ex);
        }
    }

    private Path dlqDir(String subfolder) {
        return Path.of(dlqProperties.getBasePath(), subfolder);
    }

    private void cleanupEmptyParents(Path dir, Path stopAt) {
        try {
            while (dir != null && !dir.equals(stopAt) && Files.isDirectory(dir)) {
                try (Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isEmpty()) {
                        Files.delete(dir);
                        dir = dir.getParent();
                    } else {
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "unknown";
        String s = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Limit max filename length to prevent FFmpeg path explosion issues
        return s.length() > 64 ? s.substring(s.length() - 64) : s;
    }
}
