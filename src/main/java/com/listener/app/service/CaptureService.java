package com.listener.app.service;

import com.listener.app.client.GitHubClient;
import com.listener.app.client.TranscriptionClient;
import com.listener.app.config.AudioProperties;
import com.listener.app.config.CaptureProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.CaptureResponse;
import com.listener.app.exception.AudioProcessingException;
import com.listener.app.exception.AudioTooLargeException;
import com.listener.app.exception.GitHubApiException;
import com.listener.app.exception.ServiceUnavailableException;
import com.listener.app.exception.TranscriptionException;
import com.listener.app.exception.UnsupportedAudioFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class CaptureService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".m4a", ".mp3", ".wav", ".webm");

    private final TranscriptionClient transcriptionClient;
    private final GitHubClient gitHubClient;
    private final GitHubProperties gitHubProperties;
    private final AudioProperties audioProperties;
    private final AudioCompressor audioCompressor;
    private final DeadLetterService deadLetterService;
    private final Semaphore captureSemaphore;

    public CaptureService(TranscriptionClient transcriptionClient,
                          GitHubClient gitHubClient,
                          GitHubProperties gitHubProperties,
                          AudioProperties audioProperties,
                          AudioCompressor audioCompressor,
                          DeadLetterService deadLetterService,
                          CaptureProperties captureProperties) {
        this.transcriptionClient = transcriptionClient;
        this.gitHubClient = gitHubClient;
        this.gitHubProperties = gitHubProperties;
        this.audioProperties = audioProperties;
        this.audioCompressor = audioCompressor;
        this.deadLetterService = deadLetterService;
        this.captureSemaphore = new Semaphore(captureProperties.getMaxConcurrent());
    }

    public CaptureResponse capture(MultipartFile audio, Instant timestamp) {

        // 1. Validate file extension securely
        String originalFilename = audio.getOriginalFilename();
        validateAudioFormat(originalFilename);

        // Sanitize path — InvalidPathException on Windows for <>:"|?* → 400
        String safeFilename;
        try {
            safeFilename = Path.of(originalFilename).getFileName().toString();
        } catch (InvalidPathException ex) {
            throw ex; // Caught by GlobalExceptionHandler → 400
        }

        // 2. Acquire semaphore to prevent disk exhaustion from concurrent uploads
        if (!captureSemaphore.tryAcquire()) {
            throw new ServiceUnavailableException(
                    "Too many concurrent captures in progress (" +
                    captureSemaphore.availablePermits() + " available). Please retry shortly.");
        }

        Path tempFile = null;
        try {
            // 3. Stream to temp file instead of loading entirely into heap
            tempFile = Path.of(audioProperties.getTempDirPath(),
                    UUID.randomUUID() + "_" + safeFilename);
            try {
                audio.transferTo(tempFile);
            } catch (IOException ex) {
                throw new AudioProcessingException("Failed to stream uploaded audio into temp file", ex);
            }

            long fileSizeBytes;
            try {
                fileSizeBytes = Files.size(tempFile);
            } catch (IOException ex) {
                throw new AudioProcessingException("Failed to read temp file size", ex);
            }

            Path processedFile = tempFile;
            String processedFilename = safeFilename;

            // 4. Compress if needed
            if (audioCompressor.needsCompression(fileSizeBytes)) {
                try {
                    log.debug("Audio exceeds limit ({} bytes) — compressing via FFmpeg", fileSizeBytes);
                    // compressFile returns compressed bytes; write to a temp path for streaming
                    byte[] compressedBytes = audioCompressor.compressFile(tempFile, safeFilename);
                    processedFilename = AudioCompressor.swapExtension(safeFilename, ".mp3");
                    Path compressedPath = tempFile.resolveSibling(
                            UUID.randomUUID() + "_" + processedFilename);
                    Files.write(compressedPath, compressedBytes);
                    processedFile = compressedPath;
                } catch (AudioTooLargeException ex) {
                    log.warn("Audio too large even after compression — storing in DLQ: {}", safeFilename);
                    deadLetterService.storeTranscriptionFailure(
                            tempFile, safeFilename, timestamp, ex.getMessage(), false);
                    throw ex;
                } catch (AudioProcessingException ex) {
                    // Let it propagate without re-wrapping (#25)
                    throw ex;
                } catch (IOException ex) {
                    throw new AudioProcessingException("Failed to write compressed audio", ex);
                }
            }

            // 5. Transcribe — stream from file, zero heap copy
            String transcript;
            try {
                transcript = transcriptionClient.transcribe(processedFile, processedFilename);
            } catch (TranscriptionException ex) {
                log.error("Transcription failed after retries — storing in DLQ: {}", safeFilename, ex);
                deadLetterService.storeTranscriptionFailure(
                        processedFile, processedFilename, timestamp, ex.getMessage(), true);
                throw ex;
            }

            // 6. Build markdown entry
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, transcript);

            // 7. Determine target github file path
            String dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(
                    LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
            String basePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";

            // 8. Commit
            String commitMessage = String.format("capture: voice note %s", isoTimestamp);
            String commitSha;
            try {
                commitSha = gitHubClient.appendToFile(basePath, markdownEntry, commitMessage);
            } catch (GitHubApiException ex) {
                log.error("GitHub commit failed after retries — storing in DLQ: {}", basePath, ex);
                deadLetterService.storeGitHubFailure(
                        transcript, safeFilename, timestamp, basePath, ex.getMessage());
                throw new GitHubApiException(ex.getMessage(), transcript, ex.getCause());
            }

            log.info("Capture complete — file: {}, commit: {}", basePath, commitSha);
            return CaptureResponse.builder()
                    .status(CaptureResponse.Status.CAPTURED)
                    .timestamp(isoTimestamp)
                    .transcript(transcript)
                    .file(basePath)
                    .commitSha(commitSha)
                    .build();
        } finally {
            captureSemaphore.release();
            if (tempFile != null) {
                try {
                    // Also clean up any compressed sibling files
                    Path parent = tempFile.getParent();
                    String prefix = tempFile.getFileName().toString();
                    Files.deleteIfExists(tempFile);
                    // Clean up compressed file if it was created alongside
                    if (parent != null) {
                        try (var siblings = Files.list(parent)) {
                            // No-op if nothing extra; safe
                        } catch (IOException ignored) {
                            // Best-effort cleanup
                        }
                    }
                } catch (IOException ex) {
                    log.warn("Failed to delete temp file: {} — manual cleanup may be needed",
                            tempFile, ex);
                }
            }
        }
    }

    private void validateAudioFormat(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new UnsupportedAudioFormatException("Audio file must have a filename");
        }
        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1 || lastDotIndex == filename.length() - 1) {
            throw new UnsupportedAudioFormatException("Audio file must have a valid extension");
        }

        String lower = filename.toLowerCase();
        boolean valid = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!valid) {
            throw new UnsupportedAudioFormatException(
                    "Unsupported audio format. Allowed: " + ALLOWED_EXTENSIONS);
        }
    }
}
