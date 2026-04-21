package com.listener.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.listener.app.client.GitHubClient;
import com.listener.app.client.TranscriptionClient;
import com.listener.app.config.DlqProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.DlqMetadata;
import com.listener.app.dto.GitHubFileResponse;
import com.listener.app.exception.NonRetryableUpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Dead Letter Queue for captures that failed due to external service unavailability.
 *
 * <p><b>Single-node assumption:</b> The {@code isRunning} guard is JVM-local. If multiple
 * instances run against the same DLQ directory, both will process items. For a single-node
 * deployment this is safe. Multi-node requires distributed locking (e.g. Redis, DB advisory lock).</p>
 *
 * <p><b>Deduplication:</b> Before appending a DLQ item to GitHub, the service checks if the
 * content is already present in the target file (e.g. manually retried). If found, the DLQ
 * item is deleted without re-appending.</p>
 *
 * <p><b>Write ordering:</b> Metadata is written before the audio file. On crash between the two,
 * the retry logic detects the missing audio file and skips the item (line ~182).</p>
 */
@Slf4j
@Component
public class DeadLetterService {

    private static final String TRANSCRIPTION_UNREACHABLE = "transcription-unreachable";
    private static final String GITHUB_UNREACHABLE = "github-unreachable";

    private final DlqProperties dlqProperties;
    private final GitHubProperties gitHubProperties;
    private final TranscriptionClient transcriptionClient;
    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    /**
     * JVM-local reentrancy guard. See class Javadoc for multi-node caveat.
     */
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

    // ── Store methods ───────────────────────────────────────────────────

    /**
     * Stores a transcription failure. Accepts a {@link Path} to avoid reading the
     * entire file into heap memory.
     *
     * <p><b>Write order:</b> metadata first, then audio. If crash occurs between the two,
     * the retry logic detects the missing audio file and skips.</p>
     */
    public void storeTranscriptionFailure(Path audioFile, String filename, Instant timestamp,
                                          String error, boolean autoRetryable) {
        String dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(
                LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
        String timeStr = DateTimeFormatter.ofPattern("HHmmss")
                .withZone(ZoneId.of("UTC")).format(timestamp);
        String sanitized = sanitizeFilename(filename);
        String baseName = timeStr + "_" + sanitized;
        Path dlqDir = Path.of(dlqProperties.getBasePath(), TRANSCRIPTION_UNREACHABLE, dateStr);

        try {
            Files.createDirectories(dlqDir);

            // Write metadata FIRST (crash-safe: missing audio → skip on retry)
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

            // Then copy the audio file
            Files.copy(audioFile, dlqDir.resolve(baseName), StandardCopyOption.REPLACE_EXISTING);

            log.warn("DLQ stored (transcription-unreachable): {}/{} [autoRetry={}]",
                    dateStr, baseName, autoRetryable);

        } catch (IOException ex) {
            log.error("CRITICAL — failed to store in DLQ, audio data may be lost! File: {}",
                    filename, ex);
        }
    }

    public void storeGitHubFailure(String transcript, String filename, Instant timestamp,
                                   String targetFilePath, String error) {
        String dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(
                LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
        String timeStr = DateTimeFormatter.ofPattern("HHmmss")
                .withZone(ZoneId.of("UTC")).format(timestamp);
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

    // ── Retry scheduler ─────────────────────────────────────────────────

    @Scheduled(
            fixedDelayString = "${dlq.retry-interval-ms:300000}",
            initialDelayString = "${dlq.retry-interval-ms:300000}"
    )
    public void retryFailedCaptures() {
        if (!isRunning.compareAndSet(false, true)) {
            log.debug("DLQ scheduler already running, skipping overlap.");
            return;
        }

        // Propagate MDC for log correlation (#9)
        MDC.put("requestId", "dlq-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            AtomicInteger globalFailures = new AtomicInteger(0);
            retryTranscriptionFailures(globalFailures);
            retryGitHubFailures(globalFailures);
        } finally {
            MDC.remove("requestId");
            isRunning.set(false);
        }
    }

    // ── Transcription retries ───────────────────────────────────────────

    private void retryTranscriptionFailures(AtomicInteger failures) {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), TRANSCRIPTION_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json")).sorted()) {

            metaFiles.forEach(meta -> {
                if (failures.get() >= 3) return; // Circuit breaker
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
                return true;
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

            String dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(
                    LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
            String filePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";
            String commitMessage = String.format("capture (dlq retry): voice note %s", isoTimestamp);

            // Deduplication: check if content already exists (e.g. manually retried)
            if (isContentAlreadyCommitted(filePath, isoTimestamp, transcript)) {
                log.info("DLQ item already committed to GitHub (manual retry?) — removing: {}",
                        audioFileName);
            } else {
                gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);
            }

            Files.deleteIfExists(audioPath);
            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(TRANSCRIPTION_UNREACHABLE));
            log.info("DLQ retry successful — transcription-unreachable item processed: {}", audioFileName);
            return true;

        } catch (NonRetryableUpstreamException ex) {
            // Permanent failure — skip further retries (#20)
            log.error("DLQ item hit non-retryable upstream error, marking permanently failed: {}",
                    metaPath.getFileName(), ex);
            markPermanentlyFailed(metaPath);
            return true; // Don't count as transient failure for circuit breaker
        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
            return false;
        }
    }

    // ── GitHub retries ──────────────────────────────────────────────────

    private void retryGitHubFailures(AtomicInteger failures) {
        Path dlqDir = Path.of(dlqProperties.getBasePath(), GITHUB_UNREACHABLE);
        if (!Files.exists(dlqDir)) {
            return;
        }

        try (Stream<Path> metaFiles = Files.walk(dlqDir)
                .filter(p -> p.toString().endsWith(".meta.json")).sorted()) {

            metaFiles.forEach(meta -> {
                if (failures.get() >= 3) return; // Circuit breaker
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

            // Deduplication: check if content already exists
            if (isContentAlreadyCommitted(filePath, isoTimestamp, metadata.getTranscript())) {
                log.info("DLQ item already committed to GitHub (manual retry?) — removing: {}",
                        metaPath.getFileName());
            } else {
                gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);
            }

            Files.deleteIfExists(metaPath);
            cleanupEmptyParents(metaPath.getParent(), dlqDir(GITHUB_UNREACHABLE));
            log.info("DLQ retry successful — github-unreachable item processed: {}",
                    metaPath.getFileName());
            return true;

        } catch (NonRetryableUpstreamException ex) {
            log.error("DLQ item hit non-retryable upstream error, marking permanently failed: {}",
                    metaPath.getFileName(), ex);
            markPermanentlyFailed(metaPath);
            return true;
        } catch (Exception ex) {
            log.warn("DLQ retry failed, will try again later: {}", metaPath.getFileName(), ex);
            incrementRetryCount(metaPath);
            return false;
        }
    }

    // ── Deduplication ───────────────────────────────────────────────────

    /**
     * Checks if the given transcript content is already present in the GitHub file.
     * Uses the ISO timestamp as the unique identifier — if a {@code ## <timestamp>} header
     * already exists in the file, the content was already committed (e.g. via manual retry).
     */
    private boolean isContentAlreadyCommitted(String filePath, String isoTimestamp, String transcript) {
        try {
            GitHubFileResponse existing = gitHubClient.getFile(filePath);
            if (existing == null) {
                return false;
            }
            String content = new String(
                    Base64.getMimeDecoder().decode(existing.getContent()),
                    java.nio.charset.StandardCharsets.UTF_8);

            // Check for the exact timestamp header — this is the unique identifier
            return content.contains("## " + isoTimestamp);
        } catch (Exception ex) {
            log.debug("Deduplication check failed (will proceed with append): {}", ex.getMessage());
            return false; // On failure, proceed with append (safe default)
        }
    }

    // ── Helper methods ──────────────────────────────────────────────────

    private DlqMetadata readMetadata(Path metaPath) throws IOException {
        return objectMapper.readValue(Files.readString(metaPath), DlqMetadata.class);
    }

    private void incrementRetryCount(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);
            metadata.setRetryCount(metadata.getRetryCount() + 1);

            Path tempMeta = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
            Files.writeString(tempMeta, objectMapper.writeValueAsString(metadata));
            Files.move(tempMeta, metaPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException ex) {
            log.error("Failed to update retry count for DLQ item safely: {}", metaPath, ex);
        }
    }

    /**
     * Marks a DLQ item as permanently failed by setting retryCount to maxRetryAttempts.
     * Used for non-retryable upstream errors (4xx from API).
     */
    private void markPermanentlyFailed(Path metaPath) {
        try {
            DlqMetadata metadata = readMetadata(metaPath);
            metadata.setRetryCount(dlqProperties.getMaxRetryAttempts());
            metadata.setAutoRetryable(false);

            Path tempMeta = metaPath.resolveSibling(metaPath.getFileName() + ".tmp");
            Files.writeString(tempMeta, objectMapper.writeValueAsString(metadata));
            Files.move(tempMeta, metaPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException ex) {
            log.error("Failed to mark DLQ item as permanently failed: {}", metaPath, ex);
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

    /**
     * Sanitizes a filename for safe filesystem usage.
     * Keeps the <em>prefix</em> (not tail) when truncating to preserve date/time info (#21).
     *
     * @param filename the original filename (validated non-null upstream, but guarded defensively)
     */
    String sanitizeFilename(String filename) {
        if (filename == null) return "unknown"; // Unreachable — validated upstream
        String s = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
