# ListenerApp — Engineering Instructions

> Written from the perspective of a senior backend Java engineer.
> These notes cover setup, architecture, conventions, and operational guidance.

---

## 0. Owner's Engineering Principles

> These principles apply across all projects and must be followed consistently.

1. **Every failure point gets a try-catch with proper logging.**
   Do not let exceptions bubble silently. Log at the point of failure with
   relevant context (filename, size, status code). The global exception handler
   is a safety net, not the primary logging mechanism.

2. **File size limits must be handled gracefully — adapt, don't just reject.**
   If a file is too large, compress it (smart bitrate). Only reject as a last
   resort, and always with a helpful error message explaining what to do.

3. **Every external service call must have retry.**
   Use `@Retryable` with exponential backoff. Transient failures are normal —
   the app must handle them without human intervention.

4. **Data must never be lost.**
   If a service is permanently unreachable after retries, store the payload
   in a Dead Letter Queue for automatic retry later. Audio and transcripts
   must survive any single point of failure.

5. **DLQ must auto-retry.**
   Scheduled reprocessing, not just passive storage. If the downstream service
   comes back online, the DLQ should drain itself.

6. **All exceptions must be domain-specific.**
   No raw `RuntimeException`, no generic `Exception` catches that swallow context.
   Create purpose-built exceptions (`AudioProcessingException`, `WhisperTranscriptionException`, etc.).

7. **Cost efficiency matters.**
   Compress before calling paid APIs — don't send a 50 MB WAV when a 3 MB MP3
   at 64 kbps transcribes just as accurately. Prefer single API calls (full context)
   over fragmented calls (chunks) for better accuracy and lower cost.

8. **Code for accuracy.**
   Whisper transcribes better with full audio context. Smart compression beats
   naive chunking — one API call with complete audio outperforms N calls with
   context-free fragments.

9. **Separate DLQ concerns by failure type.**
   `whisper-unreachable/` and `github-unreachable/` — different failures,
   different retry strategies, different stored payloads.

10. **Document everything in `instructions.md`.**
    Principles, architecture, conventions — so they remain consistent across
    projects and survive context switches.

---

## 1. Prerequisites

| Tool    | Version | Notes                                              |
|---------|---------|----------------------------------------------------|
| JDK     | 21      | LTS release; use Temurin or Corretto               |
| Maven   | 3.9+    | Build tool                                         |
| FFmpeg  | 6.x+    | Audio compression — [install](https://ffmpeg.org/) |
| Git     | 2.x     | Version control                                    |
| curl    | any     | Manual smoke testing                               |

---

## 2. Quick Start

```bash
# 1. Clone the repo
git clone <repo-url> && cd ListenerApp

# 2. Set your API keys (choose one method)

#    Option A — environment variables (preferred for production)
export OPENAI_API_KEY=sk-...
export GITHUB_TOKEN=ghp_...

#    Option B — edit application.yml directly (dev only, never commit secrets)
#    Replace the YOUR_*_HERE placeholders in src/main/resources/application.yml

# 3. Also set repo details in application.yml
#    github.owner, github.repo, github.branch

# 4. Build and run
mvn clean package -DskipTests
java -jar target/listener-app-0.0.1-SNAPSHOT.jar

# Or run in dev mode:
mvn spring-boot:run

# 5. Test
curl -X POST http://localhost:8080/capture \
  -F "audio=@/path/to/note.m4a"
```

---

## 3. Configuration Reference

All configuration lives in `src/main/resources/application.yml`.
Spring Boot's property binding means every YAML key can be overridden
via environment variables. The naming convention is:

| YAML key                | Env var equivalent          |
|-------------------------|-----------------------------|
| `whisper.api-key`       | `WHISPER_API_KEY`           |
| `github.token`          | `GITHUB_TOKEN`              |
| `github.owner`          | `GITHUB_OWNER`              |
| `github.repo`           | `GITHUB_REPO`               |
| `github.branch`         | `GITHUB_BRANCH`             |
| `github.inbox-path`     | `GITHUB_INBOX_PATH`         |
| `audio.ffmpeg-path`     | `AUDIO_FFMPEG_PATH`         |
| `retry.max-attempts`    | `RETRY_MAX_ATTEMPTS`        |
| `dlq.retry-interval-ms` | `DLQ_RETRY_INTERVAL_MS`    |

> **Security note:** Never commit real API keys. Use environment variables
> or Spring profiles (`application-local.yml`, which is `.gitignore`d).

---

## 4. Architecture

```
                ┌─────────────────┐
   curl/client ─┤ CaptureController│
                └────────┬────────┘
                         │
                ┌────────▼────────┐
                │  CaptureService  │  ← orchestration
                └──┬────┬──────┬──┘
                   │    │      │
        ┌──────────▼┐  │  ┌───▼──────────┐
        │AudioCompressor│  │ │ GitHubClient │ ← @Retryable
        └──────────┘  │  └──────────────┘
                      │
             ┌────────▼──┐
             │WhisperClient│ ← @Retryable
             └────────────┘
                   │
            OpenAI Whisper API

                ┌─────────────────┐
                │ DeadLetterService│ ← @Scheduled auto-retry
                └──┬───────────┬──┘
                   │           │
     dlq/whisper-  │    dlq/github-
     unreachable/  │    unreachable/
```

### Layer responsibilities

| Layer        | Role                                              | Rules                                                    |
|--------------|---------------------------------------------------|----------------------------------------------------------|
| Controller   | HTTP concerns only — parse request, return response | No business logic. No direct API calls.                  |
| Service      | Orchestrates the full pipeline                     | Coordinates clients, handles domain logic. Every external call wrapped in try-catch. |
| Client       | Single-responsibility API wrappers                 | Each client talks to exactly one external API. Throws domain exceptions. Uses @Retryable. |
| Compressor   | Audio compression via FFmpeg                       | Smart bitrate calculation. Graceful fallback if FFmpeg missing. |
| DLQ          | Failure recovery                                   | Store failed items, auto-retry on schedule. Two folders by failure type. |
| DTO          | Data transfer objects                              | Immutable where possible (`@Value`). No logic.           |
| Exception    | Domain errors with HTTP mapping                    | Centralised in `GlobalExceptionHandler`.                 |
| Config       | Type-safe configuration properties                 | `@ConfigurationProperties` — validated at startup.       |

### Key design decisions

1. **WebClient over RestTemplate** — `RestTemplate` is in maintenance mode.
   WebClient is the modern, non-blocking HTTP client in Spring. We call
   `.block()` because this app is synchronous.

2. **Compression over chunking** — Whisper has a hard 25 MB limit. Rather
   than splitting audio into chunks (which loses word-boundary context and
   requires multiple API calls), we compress to a lower bitrate. Voice at
   64 kbps MP3 transcribes identically to 256 kbps — Whisper was trained
   on noisy, compressed audio.

3. **Smart bitrate** — `targetBitrate = (24 MB × 8) / durationSeconds`,
   clamped to [32 kbps, 128 kbps]. At 32 kbps, ~109 minutes fits in 25 MB.

4. **@Retryable on appendToFile** — the entire GET→decode→append→encode→PUT
   cycle retries as a unit. If a PUT fails with 409 (stale SHA), the retry
   re-reads the file for a fresh SHA. This handles race conditions naturally.

5. **DLQ two-folder design** — `whisper-unreachable/` stores audio bytes
   (Whisper failed, GitHub works). `github-unreachable/` stores transcript
   text (Whisper worked, GitHub failed). Different payloads for different
   failure modes.

6. **Transcript preserved on GitHub failure** — per the spec, the 502
   response body includes the transcript so the caller doesn't lose work.
   Additionally, the DLQ stores it for auto-retry.

---

## 5. Code Style & Conventions

### General

- **Java 21** — use modern features (records, text blocks, pattern matching)
  where they improve clarity, but don't use them just because they exist.
- **Lombok** — use `@Data` for mutable beans (configs, deserialization DTOs),
  `@Value` + `@Builder` for immutable DTOs, `@RequiredArgsConstructor`
  for constructor injection, `@Slf4j` for logging.
- **Constructor injection** — always. Never `@Autowired` on fields.
- **Final fields** — all injected dependencies should be `final`
  (Lombok's `@RequiredArgsConstructor` handles this).

### Naming

| Element           | Convention                  | Example                          |
|-------------------|-----------------------------|----------------------------------|
| Packages          | lowercase, no underscores   | `com.listener.app.client`        |
| Classes           | PascalCase, noun            | `CaptureService`                 |
| Methods           | camelCase, verb             | `transcribe()`, `getFile()`      |
| Constants         | UPPER_SNAKE_CASE            | `ALLOWED_EXTENSIONS`             |
| Config properties | kebab-case in YAML          | `api-key`, `inbox-path`          |
| DTOs              | suffix with purpose         | `CaptureResponse`, `GitHubPutRequest` |

### Logging

- **INFO** for happy-path milestones (request received, transcription done, commit complete).
- **WARN** for recoverable issues (bad format, DLQ store, compression warnings).
- **ERROR** for failures that produce 5xx responses or data-loss risks.
- Always include relevant context (filename, size, SHA, status code).
- Never log secrets (API keys, tokens, full file content).

### Error handling

- Throw domain-specific runtime exceptions from clients/services.
- **Every** external call must be wrapped in try-catch with logging.
- Map exceptions to HTTP status codes centrally in `GlobalExceptionHandler`.
- Never return raw exception messages to callers — always wrap.
- Include actionable detail in error responses.

---

## 6. Testing Strategy

### Unit tests (not yet implemented — add as next priority)

| Class              | What to test                                                |
|--------------------|-------------------------------------------------------------|
| `CaptureService`   | Validation, compression routing, DLQ routing, markdown formatting |
| `AudioCompressor`  | Smart bitrate calculation, FFmpeg availability check        |
| `WhisperClient`    | Mock WebClient — verify request shape, retry behavior       |
| `GitHubClient`     | Mock WebClient — verify appendToFile cycle, 404 handling    |
| `DeadLetterService`| Store + retry lifecycle, file cleanup                       |

### Smoke tests

```bash
# No audio → 400
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/capture

# Unsupported format → 400
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/capture \
  -F "audio=@test.txt"

# Valid audio (requires real API keys)
curl -X POST http://localhost:8080/capture \
  -F "audio=@note.m4a"
```

---

## 7. Adding CORS (When Frontend Arrives)

When you're ready to add a frontend:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/capture")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("POST")
                .allowedHeaders("*");
    }
}
```

---

## 8. Dependency Notes

| Dependency                     | Purpose                          | Why                                         |
|--------------------------------|----------------------------------|---------------------------------------------|
| `spring-boot-starter-web`     | REST + Tomcat + multipart        | Core web framework                          |
| `spring-boot-starter-webflux` | `WebClient`                      | Modern HTTP client for external API calls   |
| `spring-retry`                | `@Retryable` + `@Recover`       | Automatic retry with exponential backoff    |
| `spring-boot-starter-aop`     | AOP proxy weaving                | Required by spring-retry for annotation proxying |
| `lombok`                      | Boilerplate reduction            | Compile-only, excluded from packaged JAR    |
| `spring-boot-starter-test`    | JUnit 5 + Mockito + Spring Test  | Test scope only                             |

> **No database dependency.** This app is stateless — all state lives in the
> GitHub repository. The DLQ uses the local filesystem for simplicity.

---

## 9. Git Workflow

```bash
# Feature branch
git checkout -b feature/add-retry-logic

# Commit with conventional messages
git commit -m "feat: add exponential backoff for Whisper calls"
git commit -m "fix: handle GitHub 409 conflict on concurrent writes"
git commit -m "docs: update instructions with retry config"

# PR → review → squash merge
```

Use **conventional commits**: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
