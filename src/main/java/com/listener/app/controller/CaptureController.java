package com.listener.app.controller;

import com.listener.app.dto.CaptureResponse;
import com.listener.app.service.CaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

/**
 * REST endpoint for audio capture.
 *
 * <pre>
 * POST /capture
 * Content-Type: multipart/form-data
 *
 * Fields:
 *   audio     — binary file (.m4a, .mp3, .wav, .webm)  [required]
 *   timestamp — ISO 8601 string                         [optional, defaults to now]
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CaptureController {

    private final CaptureService captureService;

    @PostMapping(value = "/capture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaptureResponse> capture(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "timestamp", required = false) String timestamp) {

        // Sanitize filename before logging to prevent log injection (#35)
        String safeLogName = sanitizeForLog(audio.getOriginalFilename());
        log.info("POST /capture — file: {}, size: {} bytes", safeLogName, audio.getSize());

        Instant ts = (timestamp != null && !timestamp.isBlank())
                ? Instant.parse(timestamp)
                : Instant.now();

        CaptureResponse response = captureService.capture(audio, ts);
        return ResponseEntity.ok(response);
    }

    /**
     * Strips newlines and carriage returns from a filename to prevent log injection.
     * Most logging frameworks escape these by default, but defense-in-depth is cheap.
     */
    private static String sanitizeForLog(String input) {
        if (input == null) return "null";
        return input.replace("\n", "_").replace("\r", "_");
    }
}
