package com.listener.app.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.nio.file.InvalidPathException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised exception handling for all REST endpoints.
 * Maps domain exceptions to the HTTP status codes defined in the spec.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 — no audio file in request ──────────────────────────────────

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingPart(MissingServletRequestPartException ex) {
        log.warn("Missing request part: {}", ex.getRequestPartName());
        return buildResponse(HttpStatus.BAD_REQUEST, "Audio file is required");
    }

    // ── 400 — unsupported audio extension ───────────────────────────────

    @ExceptionHandler(UnsupportedAudioFormatException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedFormat(UnsupportedAudioFormatException ex) {
        log.warn("Unsupported audio format: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // ── 400 — malformed timestamp ───────────────────────────────────────

    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<Map<String, String>> handleDateTimeParseException(java.time.format.DateTimeParseException ex) {
        log.warn("Invalid timestamp: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Invalid timestamp format. Please use ISO 8601 (e.g., 2026-04-20T14:32:00Z)");
    }

    // ── 400 — invalid path characters in filename (Windows rejects <>:"|?*) ─

    @ExceptionHandler(InvalidPathException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPath(InvalidPathException ex) {
        log.warn("Invalid filename characters: {}", ex.getInput());
        return buildResponse(HttpStatus.BAD_REQUEST,
                "Filename contains invalid characters: " + ex.getReason());
    }

    // ── 413 — audio too large (even after compression) ──────────────────

    @ExceptionHandler(AudioTooLargeException.class)
    public ResponseEntity<Map<String, String>> handleAudioTooLarge(AudioTooLargeException ex) {
        log.warn("Audio too large: {}", ex.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
    }

    // ── 413 — multipart upload exceeds configured limit ─────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload exceeds max size: {}", ex.getMessage());
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE,
                "File exceeds maximum upload size configured");
    }

    // ── 500 — audio processing / FFmpeg failure ─────────────────────────

    @ExceptionHandler(AudioProcessingException.class)
    public ResponseEntity<Map<String, String>> handleAudioProcessing(AudioProcessingException ex) {
        log.warn("Audio processing failed: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Failed to process audio file");
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── 502 — Transcription API failure ─────────────────────────────────

    @ExceptionHandler(TranscriptionException.class)
    public ResponseEntity<Map<String, String>> handleTranscriptionFailure(TranscriptionException ex) {
        log.warn("Transcription failed → 502: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Transcription API failed");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── 502 — GitHub API failure (includes transcript so it's not lost) ─

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, String>> handleGitHubFailure(GitHubApiException ex) {
        log.warn("GitHub API call failed → 502: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "GitHub commit failed");
        body.put("detail", ex.getMessage());
        if (ex.getTranscript() != null) {
            body.put("transcript", ex.getTranscript());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── 502 — Non-retryable upstream rejection (4xx from Whisper/GitHub) ─

    @ExceptionHandler(NonRetryableUpstreamException.class)
    public ResponseEntity<Map<String, String>> handleNonRetryableUpstream(NonRetryableUpstreamException ex) {
        log.warn("Non-retryable upstream error → 502: {}", ex.getMessage());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Upstream API rejected the request");
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── 503 — Service unavailable (lock contention timeout) ─────────────

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("Service unavailable: {}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    // ── 500 — Generic Catch All ─────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Unhandled server error", ex);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── helper ──────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> buildResponse(HttpStatus status, String errorMessage) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", errorMessage);
        return ResponseEntity.status(status).body(body);
    }
}
