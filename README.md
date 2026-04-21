# ListenerApp

Voice-to-Markdown capture service — receives audio via `POST /capture`, transcribes it
using OpenAI Whisper, and commits the transcript as a timestamped markdown entry to a
GitHub repository.

## Features

- **Smart Audio Compression**: Automatically compresses uploaded audio > 25 MB using FFmpeg to fit within Whisper's hard limits, maximizing context while reducing API calls.
- **Resilient External Calls**: Implements `@Retryable` with exponential backoff to handle transient Whisper and GitHub API failures.
- **Dead Letter Queue (DLQ)**: Avoids data loss during prolonged outages by routing stranded audio/transcripts to a localized DLQ folder with automatic scheduled retries.
- **Stateless Architecture**: Leverages GitHub as the persistent layer, continuously appending transcripts to daily Markdown files.

## Prerequisites

| Tool    | Version | Required | Notes |
|---------|---------|----------|-------|
| JDK     | 21+     | ✅       | LTS — use Temurin or Corretto |
| Maven   | 3.9+    | ✅       | Build tool |
| FFmpeg  | 6.x+    | ✅       | Audio compression — [download](https://ffmpeg.org/download.html) |
| Git     | 2.x     | ✅       | Version control |

> **FFmpeg** must be on your system `PATH` (or configure the absolute path in
> `application.yml` under `audio.ffmpeg-path`). Without FFmpeg, the app still works
> for files ≤25 MB but cannot compress larger uploads.

## Quick Start

```bash
# 1. Clone
git clone <repo-url> && cd ListenerApp

# 2. Configure — edit src/main/resources/application.yml
#    Replace these placeholders:
#      whisper.api-key    → your OpenAI API key
#      github.token       → your GitHub personal access token
#      github.owner       → your GitHub username
#      github.repo        → your target repo name

# 3. Build and run
mvn clean package -DskipTests
java -jar target/listener-app-0.0.1-SNAPSHOT.jar

# Or dev mode:
mvn spring-boot:run

# 4. Test
curl -X POST http://localhost:8080/capture \
  -F "audio=@/path/to/note.m4a"
```

## API

### `POST /capture`

| Field     | Type             | Required | Notes                         |
|-----------|------------------|----------|-------------------------------|
| audio     | binary file      | yes      | .m4a, .mp3, .wav, .webm      |
| timestamp | string (ISO8601) | no       | defaults to server time (UTC) |

**Response (200):**
```json
{
  "status": "captured",
  "timestamp": "2026-04-20T14:32:00Z",
  "transcript": "the transcribed text",
  "file": "inbox/2026-04-20.md",
  "commitSha": "abc123"
}
```

See [instructions.md](instructions.md) for architecture details, conventions, and
operational guidance.
