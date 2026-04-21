package com.listener.app.service;

import com.listener.app.client.GitHubClient;
import com.listener.app.client.WhisperClient;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.CaptureResponse;
import com.listener.app.exception.AudioProcessingException;
import com.listener.app.exception.AudioTooLargeException;
import com.listener.app.exception.GitHubApiException;
import com.listener.app.exception.UnsupportedAudioFormatException;
import com.listener.app.exception.WhisperTranscriptionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Orchestrates the capture flow:
 * <ol>
 *   <li>Validate audio format</li>
 *   <li>Read audio bytes</li>
 *   <li>Compress if needed (smart bitrate via FFmpeg)</li>
 *   <li>Transcribe via Whisper (with retry)</li>
 *   <li>Build markdown entry</li>
 *   <li>Commit to GitHub (with retry)</li>
 * </ol>
 *
 * <p>Failures at any stage are caught, logged, and routed to the
 * {@link DeadLetterService} so data is never lost.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".m4a", ".mp3", ".wav", ".webm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WhisperClient whisperClient;
    private final GitHubClient gitHubClient;
    private final GitHubProperties gitHubProperties;
    private final AudioCompressor audioCompressor;
    private final DeadLetterService deadLetterService;

    /**
     * Full capture pipeline: validate → compress → transcribe → commit.
     *
     * @param audio     the uploaded audio file
     * @param timestamp capture timestamp (defaults to now if not provided)
     * @return response with status, transcript, file path, and commit SHA
     */
    public CaptureResponse capture(MultipartFile audio, Instant timestamp) {

        // 1. Validate file extension and sanitize path traversal
        String originalFilename = audio.getOriginalFilename();
        if (originalFilename != null) {
            originalFilename = java.nio.file.Path.of(originalFilename).getFileName().toString();
        }
        validateAudioFormat(originalFilename);

        // 2. Read bytes
        byte[] originalBytes;
        try {
            originalBytes = audio.getBytes();
        } catch (IOException ex) {
            log.error("Failed to read uploaded audio file: {}", originalFilename, ex);
            throw new AudioProcessingException("Failed to read audio file", ex);
        }

        // 3. Compress if over Whisper's 25 MB limit
        byte[] whisperBytes = originalBytes;
        String whisperFilename = originalFilename;

        if (audioCompressor.needsCompression(originalBytes.length)) {
            try {
                log.info("Audio exceeds 25 MB ({} bytes) — compressing via FFmpeg",
                        originalBytes.length);
                whisperBytes = audioCompressor.compress(originalBytes, originalFilename);
                whisperFilename = audioCompressor.swapExtension(originalFilename, ".mp3");
            } catch (AudioTooLargeException ex) {
                log.warn("Audio too large even after compression — storing in DLQ: {}",
                        originalFilename);
                deadLetterService.storeWhisperFailure(
                        originalBytes, originalFilename, timestamp, ex.getMessage(), false);
                throw ex;  // → 413 to caller
            } catch (AudioProcessingException ex) {
                log.error("Audio compression failed: {}", originalFilename, ex);
                throw ex;  // → 500 to caller
            }
        }

        // 4. Transcribe via Whisper (retries handled by @Retryable)
        String transcript;
        try {
            transcript = whisperClient.transcribe(whisperBytes, whisperFilename);
        } catch (WhisperTranscriptionException ex) {
            log.error("Whisper transcription failed after retries — storing in DLQ: {}",
                    originalFilename, ex);
            deadLetterService.storeWhisperFailure(
                    whisperBytes, whisperFilename, timestamp, ex.getMessage(), true);
            throw ex;  // → 502 to caller, but audio is safe in DLQ
        }

        // 5. Build markdown entry
        String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
        String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, transcript);

        // 6. Determine file path:  inbox/YYYY-MM-DD.md
        String dateStr = DATE_FORMATTER.format(LocalDate.ofInstant(timestamp, ZoneOffset.UTC));
        String filePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";

        // 7. Commit to GitHub (retries handled by @Retryable on appendToFile)
        String commitMessage = String.format("capture: voice note %s", isoTimestamp);
        String commitSha;
        try {
            commitSha = gitHubClient.appendToFile(filePath, markdownEntry, commitMessage);
        } catch (GitHubApiException ex) {
            log.error("GitHub commit failed after retries — storing in DLQ: {}", filePath, ex);
            deadLetterService.storeGitHubFailure(
                    transcript, originalFilename, timestamp, filePath, ex.getMessage());
            throw new GitHubApiException(ex.getMessage(), transcript, ex.getCause());
        }

        // 8. Done
        log.info("Capture complete — file: {}, commit: {}", filePath, commitSha);
        return CaptureResponse.builder()
                .status("captured")
                .timestamp(isoTimestamp)
                .transcript(transcript)
                .file(filePath)
                .commitSha(commitSha)
                .build();
    }

    // ── private helpers ─────────────────────────────────────────────────

    private void validateAudioFormat(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new UnsupportedAudioFormatException("Audio file must have a filename");
        }
        String lower = filename.toLowerCase();
        boolean valid = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!valid) {
            throw new UnsupportedAudioFormatException(
                    "Unsupported audio format. Allowed: " + ALLOWED_EXTENSIONS);
        }
    }
}
