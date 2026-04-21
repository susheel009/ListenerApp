package com.listener.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.listener.app.client.GitHubClient;
import com.listener.app.client.WhisperClient;
import com.listener.app.config.DlqProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.DlqMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * Dead Letter Queue for captures that failed due to external service unavailability.
 *
 * <p>Storage layout:</p>
 * <pre>
 * dlq/
 * ├── whisper-unreachable/        ← Whisper down: audio stored for later transcription
 * │   └── 2026-04-20/
 * │       ├── 143200_note.m4a
 * │       └── 143200_note.m4a.meta.json
 * └── github-unreachable/         ← GitHub down: transcript stored for later commit
 *     └── 2026-04-20/
 *         └── 143200_note.meta.json
 * </pre>
 *
 * <p>A {@link Scheduled} task scans both folders periodically and retries
 * items up to {@code dlq.max-retry-attempts} times.</p>
 */
@Slf4j
@Component
public class DeadLetterService {

    private static final String WHISPER_UNREACHABLE = "whisper-unreachable";
    private static final String GITHUB_UNREACHABLE = "github-unreachable";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmmss")
            .withZone(ZoneOffset.UTC);

    private final DlqProperties dlqProperties;
    private final GitHubProperties gitHubProperties;
    private final WhisperClient whisperClient;
    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    public DeadLetterService(DlqProperties dlqProperties,
                             GitHubProperties gitHubProperties,
                             WhisperClient whisperClient,
                             GitHubClient gitHubClient,
                             ObjectMapper objectMapper) {
        this.dlqProperties = dlqProperties;
        this.gitHubProperties = gitHubProperties;
        this.whisperClient = whisperClient;
        this.gitHubClient = gitHubClient;
        this.objectMapper = objectMapper;
    }

    // ── Store methods ───────────────────────────────────────────────────

    /**
     * Stores audio for later transcription (Whisper was unreachable).
     *
     * @param audioBytes     the audio bytes that Whisper could not process
     * @param filename       original or compressed filename
     * @param timestamp      capture timestamp
     * @param error          error description
     * @param autoRetryable  false if the failure is permanent (e.g. audio too large)
     */
    public void storeWhisperFailure(byte[] audioBytes, String filename, Instant timestamp,
                                    String error, boolean autoRetryable) {
        String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneOffset.UTC));
        String timeStr = TIME_FMT.format(timestamp);
        String sanitized = sanitizeFilename(filename);
        String baseName = timeStr + "_" + sanitized;
        Path dlqDir = Path.of(dlqProperties.getBasePath(), WHISPER_UNREACHABLE, dateStr);

        try {
            Files.createDirectories(dlqDir);

            // Write audio file
            Files.write(dlqDir.resolve(baseName), audioBytes);

            // Write metadata
            DlqMetadata metadata = DlqMetadata.builder()
                    .filename(filename)
                    .timestamp(timestamp.toString())
                    .error(error)
                    .retryCount(0)
                    .failureType(WHISPER_UNREACHABLE)
                    .autoRetryable(autoRetryable)
                    .build();

            Files.writeString(dlqDir.resolve(baseName + ".meta.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));

            log.warn("DLQ stored (whisper-unreachable): {}/{} [autoRetry={}]",
                    dateStr, baseName, autoRetryable);

        } catch (IOException ex) {
            log.error("CRITICAL — failed to store in DLQ, audio data may be lost! " +
                      "File: {}, size: {} bytes", filename, audioBytes.length, ex);
        }
    }

    /**
     * Stores transcript for later GitHub commit (GitHub was unreachable).
     *
     * @param transcript     the transcribed text (Whisper succeeded)
     * @param filename       original filename
     * @param timestamp      capture timestamp
     * @param targetFilePath target file path in repo (e.g. "inbox/2026-04-20.md")
     * @param error          error description
     */
    public void storeGitHubFailure(String transcript, String filename, Instant timestamp,
                                   String targetFilePath, String error) {
        String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneOffset.UTC));
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
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));

            log.warn("DLQ stored (github-unreachable): {}/{}", dateStr, baseName);

        } catch (IOException ex) {
            log.error("CRITICAL — failed to store in DLQ, transcript may be lost! " +
                      "Transcript: {}", transcript, ex);
        }
    }

    // ── Scheduled retry ─────────────────────────────────────────────────

    /**
     * Periodically scans both DLQ folders and retries auto-retryable items.
     * Runs every {@code dlq.retry-interval-ms} milliseconds (default 5 minutes).
     */
    @Scheduled(
            fixedDelayString = "${dlq.retry-interval-ms:300000}",
            initialDelayString = "${dlq.retry-interval-ms:300000}"
    )
    public void retryFailedCaptures() {
        retryWhisperFailures();
        retryGitHubFailures();
    }

    // ── private: Whisper retry ──────────────────────────────────────────

    private void retryWhisperFailures() {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), WHISPER_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json"))) {

            metaFiles.forEach(this::retryWhisperItem);

        } catch (IOException ex) {
            log.error("Failed to scan DLQ directory: {}", dlqDir, ex);
        }
    }

    private void retryWhisperItem(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);

            if (!metadata.isAutoRetryable()) {
                log.debug("DLQ item not auto-retryable, skipping: {}", metaPath);
                return;
            }
            if (metadata.getRetryCount() >= dlqProperties.getMaxRetryAttempts()) {
                log.error("DLQ item exceeded max retries ({}), permanently failed: {}",
                        dlqProperties.getMaxRetryAttempts(), metaPath);
                return;
            }

            // Locate the audio file (same name without .meta.json)
            String metaFileName = metaPath.getFileName().toString();
            String audioFileName = metaFileName.replace(".meta.json", "");
            Path audioPath = metaPath.getParent().resolve(audioFileName);

            if (!Files.exists(audioPath)) {
                log.error("DLQ audio file missing — cannot retry: {}", audioPath);
                return;
            }

            log.info("DLQ retry (whisper-unreachable) — attempt {}/{}: {}",
                    metadata.getRetryCount() + 1, dlqProperties.getMaxRetryAttempts(), audioFileName);

            // Retry transcription
            byte[] audioBytes = Files.readAllBytes(audioPath);
            String transcript = whisperClient.transcribe(audioBytes, metadata.getFilename());

            // Transcription succeeded — commit to GitHub
            Instant timestamp = Instant.parse(metadata.getTimestamp());
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, transcript);

            String dateStr = DATE_FMT.format(LocalDate.ofInstant(timestamp, ZoneOffset.UTC));
            String filePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";
            String commitMessage = String.format("capture (dlq retry): voice note %s", isoTimestamp);

            gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);

            // Success — clean up DLQ files
            Files.deleteIfExists(audioPath);
            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(WHISPER_UNREACHABLE));
            log.info("DLQ retry successful — whisper-unreachable item processed: {}", audioFileName);

        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
        }
    }

    // ── private: GitHub retry ───────────────────────────────────────────

    private void retryGitHubFailures() {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), GITHUB_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json"))) {

            metaFiles.forEach(this::retryGitHubItem);

        } catch (IOException ex) {
            log.error("Failed to scan DLQ directory: {}", dlqDir, ex);
        }
    }

    private void retryGitHubItem(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);

            if (metadata.getRetryCount() >= dlqProperties.getMaxRetryAttempts()) {
                log.error("DLQ item exceeded max retries ({}), permanently failed: {}",
                        dlqProperties.getMaxRetryAttempts(), metaPath);
                return;
            }

            log.info("DLQ retry (github-unreachable) — attempt {}/{}: {}",
                    metadata.getRetryCount() + 1, dlqProperties.getMaxRetryAttempts(),
                    metaPath.getFileName());

            // Reconstruct markdown entry from stored transcript
            Instant timestamp = Instant.parse(metadata.getTimestamp());
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, metadata.getTranscript());

            String filePath = metadata.getTargetFilePath();
            String commitMessage = String.format("capture (dlq retry): voice note %s", isoTimestamp);

            gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);

            // Success — clean up
            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(GITHUB_UNREACHABLE));
            log.info("DLQ retry successful — github-unreachable item processed: {}",
                    metaPath.getFileName());

        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
        }
    }

    // ── private helpers ─────────────────────────────────────────────────

    private DlqMetadata readMetadata(Path metaPath) throws IOException {
        return objectMapper.readValue(Files.readString(metaPath), DlqMetadata.class);
    }

    private void incrementRetryCount(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);
            metadata.setRetryCount(metadata.getRetryCount() + 1);
            Files.writeString(metaPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        } catch (IOException ex) {
            log.error("Failed to update retry count for DLQ item: {}", metaPath, ex);
        }
    }

    private Path dlqDir(String subfolder) {
        return Path.of(dlqProperties.getBasePath(), subfolder);
    }

    /**
     * Removes empty date directories after DLQ items are cleaned up.
     */
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
            // best-effort cleanup
        }
    }

    /**
     * Replaces characters that are invalid in filenames.
     */
    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
