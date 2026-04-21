package com.listener.app.service;

import com.listener.app.client.GitHubClient;
import com.listener.app.client.TranscriptionClient;
import com.listener.app.config.AudioProperties;
import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.CaptureResponse;
import com.listener.app.exception.AudioProcessingException;
import com.listener.app.exception.AudioTooLargeException;
import com.listener.app.exception.GitHubApiException;
import com.listener.app.exception.TranscriptionException;
import com.listener.app.exception.UnsupportedAudioFormatException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaptureService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".m4a", ".mp3", ".wav", ".webm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TranscriptionClient transcriptionClient;
    private final GitHubClient gitHubClient;
    private final GitHubProperties gitHubProperties;
    private final AudioProperties audioProperties;
    private final AudioCompressor audioCompressor;
    private final DeadLetterService deadLetterService;

    public CaptureResponse capture(MultipartFile audio, Instant timestamp) {

        // 1. Validate file extension securely
        String originalFilename = audio.getOriginalFilename();
        validateAudioFormat(originalFilename);
        String safeFilename = java.nio.file.Path.of(originalFilename).getFileName().toString();

        File tempFile = null;
        try {
            // 2. Stream to temp file instead of loading entirely into heap
            tempFile = new File(audioProperties.getTempDirPath(), UUID.randomUUID() + "_" + safeFilename);
            try {
                audio.transferTo(tempFile);
            } catch (IOException ex) {
                throw new AudioProcessingException("Failed to stream uploaded audio into temp file", ex);
            }

            long fileSizeBytes = tempFile.length();
            byte[] processedBytes;
            String processedFilename = safeFilename;

            // 3. Compress if needed
            if (audioCompressor.needsCompression(fileSizeBytes)) {
                try {
                    log.debug("Audio exceeds limit ({} bytes) — compressing via FFmpeg", fileSizeBytes);
                    processedBytes = audioCompressor.compressFile(tempFile.toPath(), safeFilename);
                    processedFilename = audioCompressor.swapExtension(safeFilename, ".mp3");
                } catch (AudioTooLargeException ex) {
                    log.warn("Audio too large even after compression — storing in DLQ: {}", safeFilename);
                    try {
                        byte[] audioBackup = Files.readAllBytes(tempFile.toPath());
                        deadLetterService.storeTranscriptionFailure(
                                audioBackup, safeFilename, timestamp, ex.getMessage(), false);
                    } catch (IOException ioException) {
                        log.error("Failed to read audio backup for DLQ", ioException);
                    }
                    throw ex; 
                } catch (AudioProcessingException ex) {
                    log.error("Audio compression failed: {}", safeFilename, ex);
                    throw new AudioProcessingException("Audio compression failed", ex); 
                }
            } else {
                try {
                    processedBytes = Files.readAllBytes(tempFile.toPath());
                } catch (IOException ex) {
                    throw new AudioProcessingException("Failed to read temp audio file", ex);
                }
            }

            // 4. Transcribe 
            String transcript;
            try {
                transcript = transcriptionClient.transcribe(processedBytes, processedFilename);
            } catch (TranscriptionException ex) {
                log.error("Transcription failed after retries — storing in DLQ: {}", safeFilename, ex);
                deadLetterService.storeTranscriptionFailure(
                        processedBytes, processedFilename, timestamp, ex.getMessage(), true);
                throw ex;  
            }

            // 5. Build markdown entry
            String isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(timestamp);
            String markdownEntry = String.format("## %s%n%s%n%n", isoTimestamp, transcript);

            // 6. Determine target github file path
            String dateStr = DATE_FORMATTER.format(LocalDate.ofInstant(timestamp, ZoneId.of("UTC")));
            String basePath = gitHubProperties.getInboxPath() + "/" + dateStr + ".md";

            // 7. Commit
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
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private void validateAudioFormat(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new UnsupportedAudioFormatException("Audio file must have a filename");
        }
        // Strict mapping check that verifies an extension was supplied
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
