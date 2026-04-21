package com.listener.app.client;

import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.GitHubFileResponse;
import com.listener.app.dto.GitHubPutRequest;
import com.listener.app.dto.GitHubPutResponse;
import com.listener.app.exception.GitHubApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reads and writes files in a GitHub repository via the Contents API.
 *
 * <p>The high-level {@link #appendToFile} method is retryable — if a PUT fails
 * (e.g. 409 due to stale SHA), the retry re-reads the file for a fresh SHA
 * and re-attempts the write.</p>
 */
@Slf4j
@Component
public class GitHubClient {

    private static final String GITHUB_API_BASE = "https://api.github.com";

    private final WebClient webClient;
    private final GitHubProperties properties;

    public GitHubClient(GitHubProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader("Authorization", "Bearer " + properties.getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    // ── High-level API (retryable) ──────────────────────────────────────

    /**
     * Appends content to a file in the repository (creates it if missing).
     *
     * <p>This is the primary method for committing transcripts. The entire
     * read-append-write cycle is retried as a unit, so stale-SHA issues
     * resolve naturally on retry.</p>
     *
     * @param path            file path relative to repo root
     * @param contentToAppend content to append to the file
     * @param commitMessage   Git commit message
     * @return the commit SHA of the new commit
     * @throws GitHubApiException if all retry attempts fail
     */
    @Retryable(
            retryFor = GitHubApiException.class,
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String appendToFile(String path, String contentToAppend, String commitMessage) {
        log.info("GitHub appendToFile: {}", path);

        // 1. Read current file (or start fresh)
        GitHubFileResponse existing = getFile(path);
        String existingContent = "";
        String sha = null;

        if (existing != null) {
            existingContent = new String(
                    Base64.getMimeDecoder().decode(existing.getContent()),
                    StandardCharsets.UTF_8);
            sha = existing.getSha();
            log.info("Existing file found — {} bytes, sha: {}", existingContent.length(),
                    sha.substring(0, Math.min(sha.length(), 7)));
        } else {
            log.info("No existing file — will create: {}", path);
        }

        // 2. Append + encode
        String updatedContent = existingContent + contentToAppend;
        String base64Content = Base64.getEncoder().encodeToString(
                updatedContent.getBytes(StandardCharsets.UTF_8));

        // 3. Commit
        return putFile(path, commitMessage, base64Content, sha);
    }

    /**
     * Recovery method when all appendToFile retries are exhausted.
     */
    @Recover
    public String recoverAppendToFile(GitHubApiException ex, String path,
                                      String contentToAppend, String commitMessage) {
        log.error("GitHub appendToFile permanently failed after all retries — path: {}", path);
        throw ex;
    }

    // ── Low-level API (not retryable — called internally) ───────────────

    /**
     * Reads a file from the repository.
     *
     * @param path file path relative to repo root
     * @return file metadata with Base64 content and SHA, or {@code null} if 404
     * @throws GitHubApiException for any non-404 error
     */
    public GitHubFileResponse getFile(String path) {
        log.debug("GitHub GET contents: {}", path);
        String uri = buildContentsUri(path);

        try {
            return webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(GitHubFileResponse.class)
                    .block();

        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                log.info("File not found on GitHub (will create new): {}", path);
                return null;
            }
            log.error("GitHub GET failed with {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new GitHubApiException(
                    "GitHub GET failed with " + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(),
                    null, ex);
        } catch (Exception ex) {
            log.error("GitHub GET failed: {}", ex.getMessage(), ex);
            throw new GitHubApiException("GitHub GET failed: " + ex.getMessage(), null, ex);
        }
    }

    /**
     * Creates or updates a file in the repository.
     *
     * @param path          file path relative to repo root
     * @param commitMessage commit message
     * @param base64Content full file content, Base64-encoded
     * @param sha           blob SHA of the existing file (null for new files)
     * @return the commit SHA
     * @throws GitHubApiException if the PUT fails
     */
    public String putFile(String path, String commitMessage, String base64Content, String sha) {
        log.info("GitHub PUT contents: {} (sha: {})", path,
                sha != null ? sha.substring(0, Math.min(sha.length(), 7)) : "new file");

        String uri = buildContentsUri(path);

        GitHubPutRequest request = GitHubPutRequest.builder()
                .message(commitMessage)
                .content(base64Content)
                .sha(sha)
                .branch(properties.getBranch())
                .build();

        try {
            GitHubPutResponse response = webClient.put()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GitHubPutResponse.class)
                    .block();

            String commitSha = (response != null && response.getCommit() != null)
                    ? response.getCommit().getSha()
                    : "unknown";

            log.info("GitHub commit successful: {}", commitSha);
            return commitSha;

        } catch (WebClientResponseException ex) {
            log.error("GitHub PUT failed with {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new GitHubApiException(
                    "GitHub PUT failed with " + ex.getStatusCode() + ": " + ex.getResponseBodyAsString(),
                    null, ex);
        } catch (Exception ex) {
            log.error("GitHub PUT failed: {}", ex.getMessage(), ex);
            throw new GitHubApiException("GitHub PUT failed: " + ex.getMessage(), null, ex);
        }
    }

    // ── private helpers ─────────────────────────────────────────────────

    private String buildContentsUri(String path) {
        return String.format("/repos/%s/%s/contents/%s",
                properties.getOwner(), properties.getRepo(), path);
    }
}
