package com.listener.app.client;

import com.listener.app.config.GitHubProperties;
import com.listener.app.dto.GitHubFileResponse;
import com.listener.app.dto.GitHubPutRequest;
import com.listener.app.dto.GitHubPutResponse;
import com.listener.app.exception.GitHubApiException;
import com.listener.app.exception.NonRetryableUpstreamException;
import com.listener.app.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes files in a GitHub repository via the Contents API.
 * Uses RestClient with Apache HttpClient 5 connection pooling.
 *
 * <p><b>Locking strategy:</b> A {@link ConcurrentHashMap} of {@link ReentrantLock}s keyed
 * by the <em>base path</em> (e.g. {@code inbox/2026-04-20}) — never the rolled-over path.
 * Entries are never removed (at most ~365/year, negligible). This eliminates the classic
 * lock-map race where Thread A removes an entry that Thread B just acquired.</p>
 *
 * <p><b>Retry strategy:</b> {@code @Retryable} is on the inner {@code appendToFileWithRetry}
 * method so the lock in the outer wrapper is held only for a single attempt, not across backoff
 * sleeps.</p>
 */
@Slf4j
@Component
public class GitHubClient {

    private static final String GITHUB_API_BASE = "https://api.github.com";

    /**
     * Rollover threshold in UTF-8 bytes. GitHub Contents API has a 1 MB hard limit;
     * we roll over at 800 KB to leave headroom.
     */
    private static final int ROLLOVER_THRESHOLD_BYTES = 800 * 1024;

    private static final long LOCK_TIMEOUT_SECONDS = 90;

    private final RestClient restClient;
    private final GitHubProperties properties;

    /**
     * Per-path locks keyed by the stable base path (no rollover suffix).
     * Entries are never removed — at most ~365 per year, negligible memory.
     */
    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    public GitHubClient(GitHubProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;

        PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(20);
        connManager.setDefaultMaxPerRoute(10);

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connManager)
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(10_000);
        factory.setConnectionRequestTimeout(10_000);

        this.restClient = restClientBuilder
                .requestFactory(factory)
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader("Authorization", "Bearer " + properties.getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Appends content to a file. Locks on the stable base path so rollover
     * does not drift the lock key. Lock held only for a single attempt;
     * retries re-acquire.
     */
    public String appendToFile(String path, String contentToAppend, String commitMessage) {
        // Lock on the stable base path, never the rollover-suffixed path
        String basePath = stripMdExtension(path);

        ReentrantLock lock = fileLocks.computeIfAbsent(basePath, k -> new ReentrantLock());
        try {
            if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new ServiceUnavailableException(
                        "Timed out waiting for lock on " + basePath +
                        " — another capture for this date is still in progress. Please retry.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted waiting for lock on " + basePath, ex);
        }

        try {
            return appendToFileWithRetry(path, contentToAppend, commitMessage);
        } finally {
            lock.unlock();
            // Do NOT remove the lock entry — prevents the classic lock-map race
        }
    }

    /**
     * Inner retryable method. Called while the caller already holds the lock.
     * On retry, spring-retry re-invokes this method (which re-reads the SHA).
     */
    @Retryable(
            retryFor = GitHubApiException.class,
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String appendToFileWithRetry(String path, String contentToAppend, String commitMessage) {
        String basePath = stripMdExtension(path);
        String actualPath = path;

        log.debug("GitHub appendToFile: {}", actualPath);

        int filePart = 1;
        GitHubFileResponse existing = getFile(actualPath);
        String existingContent = "";
        String sha = null;

        // Rollover: check size in UTF-8 bytes, not Java char count
        while (existing != null) {
            existingContent = new String(
                    Base64.getMimeDecoder().decode(existing.getContent()),
                    StandardCharsets.UTF_8);

            int utf8ByteLength = existingContent.getBytes(StandardCharsets.UTF_8).length;
            if (utf8ByteLength > ROLLOVER_THRESHOLD_BYTES) {
                log.info("File {} exceeds {} bytes (actual: {} bytes). Rolling over.",
                        actualPath, ROLLOVER_THRESHOLD_BYTES, utf8ByteLength);
                actualPath = basePath + "-" + filePart + ".md";
                filePart++;
                existing = getFile(actualPath);
            } else {
                sha = existing.getSha();
                log.debug("Existing file found — {} UTF-8 bytes, sha: {}",
                        utf8ByteLength, sha.substring(0, Math.min(sha.length(), 7)));
                break;
            }
        }

        if (existing == null) {
            existingContent = "";
            sha = null;
            log.debug("No existing file — will create: {}", actualPath);
        }

        // Append + encode
        String updatedContent = existingContent + contentToAppend;
        String base64Content = Base64.getEncoder().encodeToString(
                updatedContent.getBytes(StandardCharsets.UTF_8));

        // Commit
        return putFile(actualPath, commitMessage, base64Content, sha);
    }

    @Recover
    public String recoverAppendToFileWithRetry(GitHubApiException ex, String path,
                                               String contentToAppend, String commitMessage) {
        log.debug("GitHub appendToFile retries exhausted — path: {}", path);
        throw ex;
    }

    public GitHubFileResponse getFile(String path) {
        log.debug("GitHub GET contents: {} (ref: {})", path, properties.getBranch());

        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(buildContentsUri(path))
                            .queryParam("ref", properties.getBranch())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                            // Handled below in the RestClientResponseException catch
                        } else {
                            throw new GitHubApiException(
                                    "GitHub GET failed with " + response.getStatusCode(), null);
                        }
                    })
                    .body(GitHubFileResponse.class);

        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                log.debug("File not found on GitHub (will create new): {}", path);
                return null;
            }
            log.debug("GitHub GET failed with {}", ex.getStatusCode());
            throw new GitHubApiException("GitHub GET HTTP error: " + ex.getStatusCode(), null, ex);
        } catch (Exception ex) {
            log.debug("GitHub GET failed: {}", ex.getMessage());
            throw new GitHubApiException("GitHub GET failed: " + ex.getMessage(), null, ex);
        }
    }

    public String putFile(String path, String commitMessage, String base64Content, String sha) {
        log.debug("GitHub PUT contents: {} (sha: {})", path,
                sha != null ? sha.substring(0, Math.min(sha.length(), 7)) : "new file");

        String uri = buildContentsUri(path);

        GitHubPutRequest request = GitHubPutRequest.builder()
                .message(commitMessage)
                .content(base64Content)
                .sha(sha)
                .branch(properties.getBranch())
                .build();

        try {
            GitHubPutResponse response = restClient.put()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GitHubPutResponse.class);

            String commitSha = (response != null && response.getCommit() != null)
                    ? response.getCommit().getSha()
                    : "unknown";

            log.info("GitHub commit successful: {}", commitSha);
            return commitSha;

        } catch (RestClientResponseException ex) {
            log.debug("GitHub PUT failed with {}", ex.getStatusCode());

            // 409 (SHA conflict) and 429 (rate limit) are retryable
            if (ex.getStatusCode().is4xxClientError()
                    && ex.getStatusCode().value() != 409
                    && ex.getStatusCode().value() != 429) {
                throw new NonRetryableUpstreamException(
                        "Non-retryable GitHub error: " + ex.getStatusCode(), ex);
            }

            throw new GitHubApiException("GitHub PUT failed with " + ex.getStatusCode(), null, ex);
        } catch (Exception ex) {
            log.debug("GitHub PUT failed: {}", ex.getMessage());
            throw new GitHubApiException("GitHub PUT failed: " + ex.getMessage(), null, ex);
        }
    }

    private String buildContentsUri(String path) {
        return String.format("/repos/%s/%s/contents/%s",
                properties.getOwner(), properties.getRepo(), path);
    }

    private static String stripMdExtension(String path) {
        return path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
    }
}
