package com.listener.app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata stored alongside DLQ items for tracking retry state.
 * Serialized to JSON in the DLQ directory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DlqMetadata {

    /** Original filename of the audio upload. */
    private String filename;

    /** ISO 8601 timestamp of the original capture. */
    private String timestamp;

    /** Error message from the failing service. */
    private String error;

    /** Number of retry attempts so far. */
    private int retryCount;

    /** "whisper-unreachable" or "github-unreachable". */
    private String failureType;

    /** Whether this item should be auto-retried by the scheduler. */
    private boolean autoRetryable;

    /** Transcript text (only populated for github-unreachable items). */
    private String transcript;

    /** Target file path in the repo (only for github-unreachable, e.g. "inbox/2026-04-20.md"). */
    private String targetFilePath;
}
