package com.listener.app.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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
                "File exceeds maximum upload size of 50 MB");
    }

    // ── 500 — audio processing / FFmpeg failure ─────────────────────────

    @ExceptionHandler(AudioProcessingException.class)
    public ResponseEntity<Map<String, String>> handleAudioProcessing(AudioProcessingException ex) {
        log.error("Audio processing failed", ex);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Failed to process audio file");
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── 502 — Whisper API failure ───────────────────────────────────────

    @ExceptionHandler(WhisperTranscriptionException.class)
    public ResponseEntity<Map<String, String>> handleWhisperFailure(WhisperTranscriptionException ex) {
        log.error("Whisper transcription failed", ex);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "Whisper transcription failed");
        body.put("detail", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── 502 — GitHub API failure (includes transcript so it's not lost) ─

    @ExceptionHandler(GitHubApiException.class)
    public ResponseEntity<Map<String, String>> handleGitHubFailure(GitHubApiException ex) {
        log.error("GitHub API call failed", ex);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "GitHub commit failed");
        body.put("detail", ex.getMessage());
        if (ex.getTranscript() != null) {
            body.put("transcript", ex.getTranscript());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    // ── helper ──────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> buildResponse(HttpStatus status, String errorMessage) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", errorMessage);
        return ResponseEntity.status(status).body(body);
    }
}
