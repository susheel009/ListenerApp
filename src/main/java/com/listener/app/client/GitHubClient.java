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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes files in a GitHub repository via the Contents API.
 * Uses RestClient to avoid reactive event-loop blocking.
 */
@Slf4j
@Component
public class GitHubClient {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final int GITHUB_FILE_LIMIT_BYTES = 800 * 1024; // 800 KB rollover limit

    private final RestClient restClient;
    private final GitHubProperties properties;
    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    public GitHubClient(GitHubProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        
        this.restClient = restClientBuilder
                .requestFactory(factory)
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader("Authorization", "Bearer " + properties.getToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Appends content to a file. Implements locking per-path to avoid local JVM races.
     * Rolls over to a new file suffix if the size exceeds 800 KB.
     */
    @Retryable(
            retryFor = GitHubApiException.class,
            noRetryFor = {IllegalArgumentException.class},
            maxAttemptsExpression = "${retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${retry.initial-delay-ms:1000}",
                    multiplierExpression = "${retry.multiplier:2.0}"
            )
    )
    public String appendToFile(String path, String contentToAppend, String commitMessage) {
        // Strip .md for rollover logic
        String baseFilePath = path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
        String actualPath = path;
        
        ReentrantLock lock = fileLocks.computeIfAbsent(actualPath, k -> new ReentrantLock());
        lock.lock();
        
        try {
            log.info("GitHub appendToFile (locked): {}", actualPath);

            int filePart = 1;
            GitHubFileResponse existing = getFile(actualPath);
            String existingContent = "";
            String sha = null;

            // Handle Rollover checking
            while (existing != null) {
                existingContent = new String(
                        Base64.getMimeDecoder().decode(existing.getContent()),
                        StandardCharsets.UTF_8);
                        
                if (existingContent.length() > GITHUB_FILE_LIMIT_BYTES) {
                    log.info("File {} exceeds 800KB. Rolling over to next part.", actualPath);
                    actualPath = baseFilePath + "-" + filePart + ".md";
                    filePart++;
                    existing = getFile(actualPath);
                } else {
                    sha = existing.getSha();
                    log.info("Existing file found — {} bytes, sha: {}", existingContent.length(),
                            sha.substring(0, Math.min(sha.length(), 7)));
                    break;
                }
            }

            if (existing == null) {
                existingContent = "";
                sha = null;
                log.info("No existing file — will create: {}", actualPath);
            }

            // 2. Append + encode
            String updatedContent = existingContent + contentToAppend;
            String base64Content = Base64.getEncoder().encodeToString(
                    updatedContent.getBytes(StandardCharsets.UTF_8));

            // 3. Commit
            return putFile(actualPath, commitMessage, base64Content, sha);
            
        } finally {
            lock.unlock();
            fileLocks.remove(actualPath, lock); // Optional: clean up unused locks
        }
    }

    @Recover
    public String recoverAppendToFile(GitHubApiException ex, String path,
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
                            // Caught below
                        } else {
                            throw new GitHubApiException("GitHub GET failed with " + response.getStatusCode(), null);
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
            // Do not include ex.getResponseBodyAsString() to avoid token leak
            
            // Check if 409, 429 or 5xx, otherwise it might not be retryable
            if (ex.getStatusCode().is4xxClientError() && 
               !(ex.getStatusCode().value() == 409 || ex.getStatusCode().value() == 429)) {
                throw new IllegalArgumentException("Non-retryable GitHub error: " + ex.getStatusCode(), ex);
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
}
