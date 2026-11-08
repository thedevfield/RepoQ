# RepoQ

Gradle multi-project for independent Project and Interview Spring Boot services. Project Service supports owner-scoped projects, multiple Public/Private repository registration, GitHub App consent/revocation and a Public-only analysis worker. The worker is disabled by default. Interview and AI APIs are not implemented.

Project API startup requires `PROJECT_AUTH_ISSUER` and `PROJECT_AUTH_AUDIENCE` matching DevField-Auth, `PROJECT_AUTH_JWKS_URI`, `PROJECT_AUTH_SESSION_CHECK_URI`, and the raw Project-only `PROJECT_AUTH_SERVICE_KEY`. Copy the names from `.env.example` into the ignored `.env.local`; do not commit secrets. `PROJECT_GITHUB_TOKEN` is optional for public repository metadata but is recommended when unauthenticated GitHub API rate limits are insufficient. All Project API calls require a Bearer access token. Project verifies the JWT locally, then calls Auth's session check with its service credential on each protected request. Auth unavailability blocks those requests.

`POST /api/projects` accepts the existing single repository input or a project name and repositories array. Owner-scoped P-01/P-02/P-03/P-07/P-08 remain supported; Private registration still requires its existing feature gate, GitHub App access and consent. The [Web BFF contract](docs/github-app-bff-contract.md) describes the connection flow. Interview flags remain false.

## Public analysis worker

Set `PROJECT_ANALYSIS_ENABLED=true` explicitly to process existing QUEUED jobs. Default is false; no worker threads or automatic consumption run when disabled. Java 21 **JDK** (including its compiler) and the Public source client are required. A separate single-thread executor runs one job per instance with no waiting memory queue; a dedicated 5-second poller does not occupy the GitHub approval cleanup scheduler. V7 lease/attempt recovery and V8 atomic Snapshot storage are reused; V9 adds repository checkpoints and a separate failure counter. Claims and writes retain USER -> PROJECT fence -> project -> job lock order. GitHub requests never hold those transactions.

Collection is anonymous and never uses `PROJECT_GITHUB_TOKEN` or App/user tokens. Repository ID determines current identity and Public access; the default branch commit is pinned and retained on retry. Tree and blob SHAs are checked, including Git blob hashes. Per job, at most 40 files, 100 KiB per file and 2 MiB decoded content are allowed. File/byte quotas are divided across repositories without borrowing unused shares. Code, build files and Markdown documentation are sampled; coverage records selected paths and exclusion counts. Truncated trees fail; trees are capped at 20,000 entries per repository and 50,000 per execution window. Symlinks, submodules, binaries, LFS pointers, credentials, vendor/generated directories and lockfiles are excluded. No source, build, script, external link or nested repository is executed or followed.

Each execution window permits at most 60 API requests including publication checks (configurable with `devfield.project.analysis.max-requests`, range 20-60). Blob selection reserves publication calls when the remaining members can finish in that window; otherwise completed repository results are checkpointed and publication gets a later window. The client additionally stops at the anonymous 60/hour allowance or the smaller remaining quota returned by GitHub. Retry-After/reset delays are respected; rate-limit waits do not spend the failure budget. Exhausting an execution request window, including after an extra public-proof refresh, requeues the job with a one-second delay and preserves completed file/repository results without spending failure_count. Cumulative download limits and permanent access errors retain their failure policies. `attempt_count` is a monotonic execution generation; transient errors and lease expiry increment `failure_count`. `devfield.project.analysis.max-failures` defaults to 3 (legacy `max-attempts` is a fallback), and `devfield.project.analysis.max-job-seconds` defaults to 86400, limiting total waits as well as processing. The quota gate is per process: other clients sharing the outbound IP can consume quota concurrently, so GitHub may still reject a request. Connect timeout is 3 seconds and each request/body has a 10-second deadline; response sizes are bounded. Raw response bodies, tokens and code are not logged or persisted.

V9 stores only completed per-repository sanitized Drafts, pinned SHA, policy/version identity and file/byte accounting, never source bodies. V10 reserves every Blob request in a short locked transaction before network I/O. Its job ledger enforces the cumulative 40-download / 2MiB decoded-body ceiling across attempts, divided fairly across members. Tree size, encoded length, decoded size and Blob hash must agree. Unknown outcomes retain their reservation; retrying them costs another reservation. Completed file records store only sanitized evidence and are reused without downloading again. These sealed records are not a public approval and have no API read path; reuse and V9 promotion require a fresh public proof. Expired access proofs are refreshed before another Blob request without consuming the reserved checkpoint/publication calls; an exhausted window is requeued. Each repository checkpoint refresh call is reserved before Blob selection, even when collection outlives the 30-second proof TTL. V10 conservatively seals the budget of pre-existing jobs with attempt_count > 0 because V9 cannot establish interrupted download usage; their old columns, snapshots and pointers are unchanged. Old analysis policy checkpoints cannot be reused after the symbol-resolution policy change. Completed members are not downloaded again. Publication still verifies all members freshly within the unchanged 30-second proof lifetime, using at most four parallel checks per verification and a 25-second overall wait; slow verification fails closed. Expired or incompatible checkpoints are not reused. Job terminal transitions delete checkpoints transactionally, including existing cancellation paths; the enabled poller retries bounded cleanup of expired, removed or blocked data. With the worker disabled, cleanup does not run, but checkpoints have no API read path and stale execution access is rejected. Existing Snapshot/Private X-05 deletion remains outside this feature.

Java/Spring rules use JDK parsing and semantic attribution with annotation processing and all class output disabled. Type-based CODE_FACT requires resolved JDK or installed trusted framework symbols; unresolved ancestors and types are withheld. No repository build or source is executed. Markdown technology mentions produce DOC_CLAIM; no HYPOTHESIS is generated. Unsupported syntax, unavailable dependency symbols and complex Gradle literals are conservatively left unconfirmed. Maven/Gradle dependency facts alone do not activate topics. Java evidence has declaration lines; dependency evidence covers the manifest's line range. Summaries are fixed descriptions, never source snippets. Relations remain empty without bilateral evidence. README-only or unsupported code yields INSUFFICIENT_EVIDENCE. Any Private member fails the entire job with `private_analysis_not_supported` before content requests; partial repository failures never publish READY.

Publication rechecks all members through anonymous GitHub metadata and binds a maximum 30-second proof to repository IDs and commits. The transaction rechecks proof age, fences, membership, attempt and lease before atomically publishing. P-02/P-03 verify Public access again before exposing a stored Snapshot. Remote GitHub visibility cannot be locked atomically with PostgreSQL: a visibility change immediately after verification remains a bounded race. Every subsequent result read revalidates and fails closed on Private status, rate limits or GitHub failure; evidence, SHA and Public repository identifying fields are withheld, and no token fallback is used. Existing immutable snapshots are preserved.

P-03 retains its existing fields and returns `latestReadySnapshot` only when valid. Its shape is `snapshotId`, `membershipVersion`, `members` (`memberId`, `repositoryId`, `githubRepositoryId`, `commitSha`), `topics` (string keys), `relations`, `coverage` (`memberId`, `collectedFiles`, `skippedFiles`, `selectedPaths`, `exclusions`) and `evidenceReferences` (`id`, `memberId`, `repositoryId`, `githubRepositoryId`, `commitSha`, `filePath`, `lineStart`, `lineEnd`, `claimType`, `summary`). P-02 can return READY only after validating the real snapshot. Access failure returns HTTP 200 with no snapshot/protected fields and BLOCKED repository access; a different owner gets 404 before any GitHub lookup. `canInterview` and `canPrivateInterview` remain false. Web BFF validation of this Snapshot DTO is maintained separately. P-05 is not implemented.

Tests explicitly disable the automatic worker and real source bean. Source/analyzer tests use a loopback GitHub mock; integration tests must use a separate PostgreSQL database through `PROJECT_DB_URL`/`INTERVIEW_DB_URL`, never the development database. A source client is injected only against the mock for worker tests. Live GitHub content E2E is not part of this test suite.

## Public X-01

`GET /internal/projects/{projectId}/interview-data` requires the original user Bearer JWT, an active AUTH-15 session, and the `interview` service identity. Set `PROJECT_X01_INTERVIEW_KEY_SHA256` to the hex SHA-256 of the dedicated raw key configured as `INTERVIEW_PROJECT_X01_SERVICE_KEY`. Keep that key separate from `INTERVIEW_AUTH_SERVICE_KEY` (Interview → Auth) and `PROJECT_AUTH_SERVICE_KEY` (Project → Auth). An unset or invalid Project hash disables X-01 access.

Configure Interview with `INTERVIEW_AUTH_ISSUER`, `INTERVIEW_AUTH_AUDIENCE`, `INTERVIEW_AUTH_JWKS_URI`, `INTERVIEW_AUTH_SESSION_CHECK_URI`, and `INTERVIEW_AUTH_SERVICE_KEY`. `INTERVIEW_PROJECT_BASE_URL` is a fixed Project origin. The client forwards only `PUBLIC_ONLY` and validates the bounded response before returning it. X-01 returns the Snapshot ID/version, members, topics with their stored primary evidence IDs, and the referenced code facts and supported relations. Interview user routes remain disabled.

Production requires HTTPS or an equivalently protected transport path (such as a TLS-encrypted service mesh) for Interview → Project and Interview → Auth. Use plain HTTP only for local loopback development and tests.

## Interview session API (I-01 / I-03)

The FREE session API is disabled by default. Enable it with `INTERVIEW_SESSIONS_ENABLED=true` for integration testing. I-01 returns 201 with a new session ID and pins the Public X-01 Snapshot and requested active topic or `RANDOM`. Each successful POST creates a separate session. I-03 returns owner-only session metadata. `PREPARING` records the initial state. MOCK is outside this API increment.

The first-question worker is also disabled by default. For local tests, enable both `INTERVIEW_SESSIONS_ENABLED` and `INTERVIEW_FIRST_QUESTION_FAKE_ENABLED`. A new FREE session queues one first MAIN question using its pinned topic and code fact. I-03 returns `currentQuestion` when ready or `GENERATION_FAILED` when generation stops. Existing sessions are not queued by the migration. The fake generator makes no model or network calls.

For local Ollama, enable `INTERVIEW_SESSIONS_ENABLED` and `INTERVIEW_FIRST_QUESTION_OLLAMA_ENABLED`. Select one generator. Enabling both Ollama and the fake generator fails startup. The adapter only calls `127.0.0.1` on the configured port (default 11434), with the installed `qwen3.5:4b` model. Use Ollama's local-only mode (`OLLAMA_NO_CLOUD=1`) when starting the Ollama server. The default request timeout is 20 seconds and the maximum is 25 seconds, below the 30-second worker lease.

FREE answer submission and follow-ups are disabled by default. Set `INTERVIEW_FOLLOW_UP_ENABLED=true` alongside the session and Ollama flags to enable I-04 and the follow-up worker. I-04 accepts the current question's UUID, an answer of up to 4,000 characters, and a session-scoped `submissionId` of 1–128 ASCII letters, digits, underscores or hyphens. The first submission and identical replays return `202`; a conflicting replay or a submission for a non-current or already answered question returns `409`. I-03 returns ordered saved answers and reports `AWAITING_ANSWER`, `FOLLOW_UP_PENDING`, `QUESTIONS_COMPLETE` or `FOLLOW_UP_FAILED` once the first question is ready. The first bundle has at most three follow-ups. Ollama failure leaves accepted answers available to the owner. Evaluation is a separate step. The local model receives the current bundle's questions and answers alongside pinned code evidence, so users should keep secrets out of their answers.

Set `INTERVIEW_EVALUATION_ENABLED=true` with the session, follow-up and Ollama flags to enable FREE first-bundle evaluation. Normal question closure queues evaluation; existing closed bundles are not queued retroactively. I-03 reports `evaluationStatus` as `PENDING`, `READY` or `FAILED` for evaluation jobs. It includes the qualitative `evaluation` and reports `completedBundles: 1` only after successful publication. Stored answers remain available on failure.

Set `INTERVIEW_NEXT_BUNDLE_ENABLED=true` with the session, follow-up, evaluation and Ollama flags to enable FREE I-05. I-03 exposes `currentBundleId`, `completedBundleHistory` and the number of READY evaluations. Submit `previousBundleId` and a pinned Snapshot `topicKey` (or `RANDOM`) to `/api/interviews/{id}/bundles` after the current evaluation is READY. The first request and an identical replay return `202` with the same successor `bundleId`. A different topic for that predecessor or a request before evaluation completes returns `409`.

Set `INTERVIEW_LIFECYCLE_ENABLED=true` with the session flag to enable FREE I-06. PATCH `/api/interviews/{id}` with `PAUSE`, `RESUME` or `END` in the `action` field. Pausing preserves the current position and requeues in-flight generation without refunding consumed attempts. Ending is terminal, cancels unfinished generation and retains saved questions, answers and evaluations for review. I-03 reports `PAUSED` or `ENDED`. New answers and bundles are rejected in these states; already accepted replays retain their existing behavior.

## Interview deletion (X-02 / X-04)

Persist the first `deletionJobId` for the target and reuse it on every retry, including after a timeout or lost response. Once a deletion fence binds a target to a job ID, a different job ID for that target returns `409 deletion_conflict`, even after completion. A retry with the original ID returns `204 No Content` once cleanup is committed.

Use three independent service keys for X-01 (Interview → Project), X-02 (Project → Interview), and X-04 (Auth → Interview). Configure the SHA-256 hex hashes for the inbound deletion keys as `INTERVIEW_X02_PROJECT_KEY_SHA256` and `INTERVIEW_X04_AUTH_KEY_SHA256`. Production requires HTTPS or an equivalently protected transport path for all three calls. Plain HTTP is limited to local loopback development and tests.

## Project deletion (P-06)

`DELETE /api/projects/{id}` returns `202` with `id`, `projectStatus=DELETING` and the original `deletionJobId` while cleanup is pending. P-03 returns a redacted deletion status. The owner can repeat P-06 for `204` after completion.

Configure `PROJECT_INTERVIEW_BASE_URL` as the fixed Interview origin and `PROJECT_X02_SERVICE_KEY` as the dedicated raw key matching the Interview X-02 hash. Use HTTPS in production and plain HTTP only on loopback for local development. The Project deletion poller resumes independently of the analysis Worker. Tune `PROJECT_X02_TIMEOUT_SECONDS`, `PROJECT_DELETION_RETRY_SECONDS` and `PROJECT_DELETION_POLL_INTERVAL` as needed. A failed call leaves the project in DELETING and records a safe failure code on its fence. Only Interview's 204 permits Project cleanup.

P-06 preserves shared GitHub connections and user/repository consent history. The completed fence retains a hash of the old P-01 idempotency key, if present, to reject a replay that would recreate the deleted project.

## Project account cleanup (X-03)

`DELETE /internal/users/{userId}/data` accepts an Auth service command with `userId`, `deletionJobId`, and `state: "DELETING"`. Set `PROJECT_X03_AUTH_KEY_SHA256` to the SHA-256 hex digest of an independent X-03 key and send the raw key as `X-DevField-Service-Key` with `X-DevField-Service: auth` over protected transport. Reuse the same job ID on retry.

Project commits its USER barrier before cleanup, takes over pending P-06 work for that user, and returns 204 only after deleting Project-owned data and recording completion. The USER tombstone remains to prevent late writes. Auth coordinates Interview X-04 separately.

## Local PostgreSQL

The single local Compose entry point is compose.local.yaml. It creates a separate development container and one database with auth, project and interview schemas. The Compose file lives here for local integration only; each service owns only its schema.

Create an ignored .env.local from .env.example and replace every password placeholder with a unique local secret. From PowerShell in this directory:

1. docker compose --env-file .env.local -f compose.local.yaml up -d
2. . .\scripts\use-java21.ps1
3. . .\scripts\load-local-env.ps1
4. .\gradlew.bat build
5. .\gradlew.bat :project-service:bootRun
6. In another PowerShell process, load Java 21 and the same env, then run .\gradlew.bat :interview-service:bootRun

Health: http://localhost:18081/actuator/health and http://localhost:18082/actuator/health. Build the independent JARs with :project-service:bootJar and :interview-service:bootJar.

Stop the applications with Ctrl+C. Stop only this Compose project with docker compose --env-file .env.local -f compose.local.yaml down. Do not use the volume deletion option unless the local development data is intentionally being discarded.

The application accounts have no rights in other service schemas. Flyway migrations belong to the service whose schema they change. Project Service applies V1 through V10 at startup. Do not run an older A-stage Project binary after multiple repositories have been written; it does not maintain the new membership table.

## Isolated local Mailpit for DevField Auth

Run only `compose.mailpit.local.yaml` for test mail. It has its own Compose project and no persistent volume:

```powershell
docker compose -f compose.mailpit.local.yaml up -d
docker compose -f compose.mailpit.local.yaml down
```

SMTP is `127.0.0.1:11025` and the inbox is `http://127.0.0.1:18025`. Set `AUTH_SMTP_HOST=127.0.0.1` and `AUTH_SMTP_PORT=11025` in the ignored Auth local environment. These commands do not manage the PostgreSQL Compose project or other projects' containers.
