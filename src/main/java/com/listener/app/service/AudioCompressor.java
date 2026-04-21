package com.listener.app.service;

import com.listener.app.config.AudioProperties;
import com.listener.app.exception.AudioProcessingException;
import com.listener.app.exception.AudioTooLargeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Compresses audio files via FFmpeg so they fit under Whisper's 25 MB limit.
 *
 * <p><b>Smart bitrate strategy:</b> probes the audio duration, then calculates the
 * optimal bitrate to fit within 24 MB (1 MB safety margin). The bitrate is clamped
 * between a quality floor (32 kbps) and a ceiling (128 kbps).</p>
 *
 * <p>If FFmpeg is not installed, the app still works for files ≤25 MB — it just
 * cannot compress larger ones and will reject with a helpful error.</p>
 */
@Slf4j
@Component
public class AudioCompressor {

    private static final long SAFETY_MARGIN_BYTES = 24L * 1024 * 1024;  // 24 MB target

    private final AudioProperties properties;

    public AudioCompressor(AudioProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns {@code true} if the file exceeds Whisper's size limit and needs compression.
     */
    public boolean needsCompression(long fileSizeBytes) {
        return fileSizeBytes > properties.getWhisperMaxBytes();
    }

    /**
     * Compresses the audio to fit under 25 MB using a smart bitrate calculation.
     *
     * @param audioBytes       raw audio data
     * @param originalFilename filename with extension
     * @return compressed MP3 bytes guaranteed to be ≤25 MB
     * @throws AudioTooLargeException    if the audio is too long even at minimum bitrate
     * @throws AudioProcessingException  if FFmpeg fails or is not installed
     */
    public byte[] compress(byte[] audioBytes, String originalFilename) {
        if (!isFfmpegAvailable()) {
            throw new AudioProcessingException(
                    "FFmpeg is required for audio compression but is not installed. " +
                    "Install FFmpeg or upload files smaller than 25 MB.");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("listener-compress-");
            Path inputFile = tempDir.resolve(originalFilename);
            String outputFilename = swapExtension(originalFilename, ".mp3");
            Path outputFile = tempDir.resolve(outputFilename);

            Files.write(inputFile, audioBytes);

            // 1. Probe duration
            double durationSeconds = probeDuration(inputFile);
            log.info("Audio duration: {:.1f}s ({:.1f} min)", durationSeconds, durationSeconds / 60);

            // 2. Calculate smart bitrate
            int bitrateKbps = calculateSmartBitrate(durationSeconds);
            log.info("Smart bitrate: {} kbps (duration: {}s, target: {} MB)",
                    bitrateKbps, (int) durationSeconds, SAFETY_MARGIN_BYTES / 1024 / 1024);

            // 3. Compress via FFmpeg
            runFfmpeg(inputFile, outputFile, bitrateKbps);

            // 4. Read and validate result
            byte[] compressed = Files.readAllBytes(outputFile);
            if (compressed.length > properties.getWhisperMaxBytes()) {
                throw new AudioTooLargeException(String.format(
                        "Audio still exceeds 25 MB after compression (%d bytes, %.0f min). " +
                        "Please split your recording into shorter segments.",
                        compressed.length, durationSeconds / 60));
            }

            long reductionPct = 100 - (compressed.length * 100L / audioBytes.length);
            log.info("Compression complete — {} bytes → {} bytes ({}% reduction)",
                    audioBytes.length, compressed.length, reductionPct);

            return compressed;

        } catch (AudioTooLargeException | AudioProcessingException ex) {
            throw ex;  // re-throw domain exceptions as-is
        } catch (Exception ex) {
            throw new AudioProcessingException("Audio compression failed: " + ex.getMessage(), ex);
        } finally {
            if (tempDir != null) {
                cleanupTempDir(tempDir);
            }
        }
    }

    /**
     * Checks if FFmpeg is available on the system.
     */
    public boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder(properties.getFfmpegPath(), "-version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();  // consume output
            return p.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Swaps the file extension (e.g. "note.m4a" → "note.mp3").
     */
    public String swapExtension(String filename, String newExtension) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename) + newExtension;
    }

    // ── private helpers ─────────────────────────────────────────────────

    /**
     * Probes the audio duration in seconds using ffprobe.
     */
    private double probeDuration(Path inputFile) {
        try {
            List<String> cmd = List.of(
                    deriveFfprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    inputFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0 || output.isEmpty()) {
                throw new AudioProcessingException(
                        "FFprobe failed to detect audio duration (exit code: " + exitCode + ")");
            }

            return Double.parseDouble(output);

        } catch (AudioProcessingException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AudioProcessingException("Failed to probe audio duration", ex);
        }
    }

    /**
     * Calculates the optimal bitrate to fit within the safety margin.
     *
     * <pre>
     * targetBitrate = (24 MB × 8) / durationSeconds
     * clamped to [minBitrateKbps, maxBitrateKbps]
     * </pre>
     *
     * At 32 kbps floor: max ~109 minutes fits in 25 MB.
     * At 128 kbps cap:  max ~27 minutes (no point going higher for voice).
     */
    private int calculateSmartBitrate(double durationSeconds) {
        if (durationSeconds <= 0) {
            throw new AudioProcessingException("Invalid audio duration: " + durationSeconds);
        }

        long targetBitsPerSecond = (SAFETY_MARGIN_BYTES * 8) / (long) durationSeconds;
        int targetKbps = (int) (targetBitsPerSecond / 1000);

        int clamped = Math.max(properties.getMinBitrateKbps(),
                Math.min(properties.getMaxBitrateKbps(), targetKbps));

        if (targetKbps < properties.getMinBitrateKbps()) {
            log.warn("Calculated bitrate {} kbps is below quality floor {} kbps — " +
                     "audio may exceed 25 MB after compression",
                    targetKbps, properties.getMinBitrateKbps());
        }

        return clamped;
    }

    /**
     * Runs FFmpeg to compress input to MP3 at the specified bitrate.
     */
    private void runFfmpeg(Path inputFile, Path outputFile, int bitrateKbps) {
        try {
            List<String> command = List.of(
                    properties.getFfmpegPath(),
                    "-i", inputFile.toString(),
                    "-codec:a", "libmp3lame",
                    "-b:a", bitrateKbps + "k",
                    "-y",  // overwrite output
                    outputFile.toString()
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Must consume the output stream to prevent process from blocking
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                reader.lines().forEach(line -> log.debug("FFmpeg: {}", line));
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new AudioProcessingException(
                        "FFmpeg compression failed with exit code: " + exitCode);
            }

        } catch (AudioProcessingException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AudioProcessingException("FFmpeg execution failed", ex);
        }
    }

    /**
     * Derives the ffprobe path from the configured ffmpeg path.
     * "ffmpeg" → "ffprobe", "/usr/bin/ffmpeg" → "/usr/bin/ffprobe", etc.
     */
    private String deriveFfprobePath() {
        String ffmpegPath = properties.getFfmpegPath();
        int lastIdx = ffmpegPath.lastIndexOf("ffmpeg");
        if (lastIdx >= 0) {
            return ffmpegPath.substring(0, lastIdx) + "ffprobe" + ffmpegPath.substring(lastIdx + 6);
        }
        return "ffprobe";
    }

    /**
     * Deletes a temporary directory and all its contents.
     */
    private void cleanupTempDir(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
