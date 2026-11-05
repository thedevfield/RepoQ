# RepoQ

Gradle multi-project for independent Project and Interview Spring Boot services. Project Service supports owner-scoped projects, multiple Public/Private repository registration, GitHub App consent/revocation and a Public-only analysis worker. The worker is disabled by default. Interview and AI APIs are not implemented.

Project API startup requires `PROJECT_AUTH_ISSUER` and `PROJECT_AUTH_AUDIENCE` matching DevField-Auth, `PROJECT_AUTH_JWKS_URI`, `PROJECT_AUTH_SESSION_CHECK_URI`, and the raw Project-only `PROJECT_AUTH_SERVICE_KEY`. Copy the names from `.env.example` into the ignored `.env.local`; do not commit secrets. `PROJECT_GITHUB_TOKEN` is optional for public repository metadata but is recommended when unauthenticated GitHub API rate limits are insufficient. All Project API calls require a Bearer access token. Project verifies the JWT locally, then calls Auth's session check with its service credential on each protected request. Auth unavailability blocks those requests.

`POST /api/projects` accepts the existing single repository input or a project name and repositories array. Owner-scoped P-01/P-02/P-03/P-07/P-08 remain supported; Private registration still requires its existing feature gate, GitHub App access and consent. The [Web BFF contract](docs/github-app-bff-contract.md) describes the connection flow. Interview flags remain false.

## Public analysis worker

Set `PROJECT_ANALYSIS_ENABLED=true` explicitly to process existing QUEUED jobs. Default is false; no worker threads or automatic consumption run when disabled. Java 21 **JDK** (including its compiler) and the Public source client are required. A separate single-thread executor runs one job per instance with no waiting memory queue; a dedicated 5-second poller does not occupy the GitHub approval cleanup scheduler. V7 lease/attempt recovery and V8 atomic Snapshot storage are reused without migration changes. Claims and writes retain USER ? PROJECT fence ? project ? job lock order. GitHub requests never hold those transactions.

Collection is anonymous and never uses `PROJECT_GITHUB_TOKEN` or App/user tokens. Repository ID determines current identity and Public access; the default branch commit is pinned and retained on retry. Tree and blob SHAs are checked, including Git blob hashes. Per job, at most 40 files, 100 KiB per file and 2 MiB decoded content are allowed. File/byte quotas are divided across repositories without borrowing unused shares. Code, build files and Markdown documentation are sampled; coverage records selected paths and exclusion counts. Truncated trees fail; trees are capped at 20,000 entries per repository and 50,000 per job. Symlinks, submodules, binaries, LFS pointers, credentials, vendor/generated directories and lockfiles are excluded. No source, build, script, external link or nested repository is executed or followed.

Collection permits at most 140 API requests per attempt plus at most 20 publication access checks. The client additionally stops at the anonymous 60/hour allowance or the smaller remaining quota returned by GitHub. Retry-After/reset delays are respected; only transient failures retry within the existing attempt limit. The quota gate is per process: other clients sharing the outbound IP can consume quota concurrently, so GitHub may still reject a request. Connect timeout is 3 seconds and each request/body has a 10-second deadline; response sizes are bounded. Raw response bodies, tokens and code are not logged or persisted.

Java/Spring rules use syntax-only JDK parsing with annotation processing disabled. Known explicit imports and declarations produce CODE_FACT, Markdown technology mentions produce DOC_CLAIM; no HYPOTHESIS is generated. Unsupported syntax, wildcard imports and complex Gradle literals are conservatively left unconfirmed. Maven/Gradle dependency facts alone do not activate topics. Java evidence has declaration lines; dependency evidence covers the manifest's line range. Summaries are fixed descriptions, never source snippets. Relations remain empty without bilateral evidence. README-only or unsupported code yields INSUFFICIENT_EVIDENCE. Any Private member fails the entire job with `private_analysis_not_supported` before content requests; partial repository failures never publish READY.

Publication rechecks all members through anonymous GitHub metadata and binds a maximum 30-second proof to repository IDs and commits. The transaction rechecks proof age, fences, membership, attempt and lease before atomically publishing. P-02/P-03 verify Public access again before exposing a stored Snapshot. Remote GitHub visibility cannot be locked atomically with PostgreSQL: a visibility change immediately after verification remains a bounded race. Every subsequent result read revalidates and fails closed on Private status, rate limits or GitHub failure; evidence, SHA and Public repository identifying fields are withheld, and no token fallback is used. Existing immutable snapshots are preserved.

P-03 retains its existing fields and returns `latestReadySnapshot` only when valid. Its shape is `snapshotId`, `membershipVersion`, `members` (`memberId`, `repositoryId`, `githubRepositoryId`, `commitSha`), `topics` (string keys), `relations`, `coverage` (`memberId`, `collectedFiles`, `skippedFiles`, `selectedPaths`, `exclusions`) and `evidenceReferences` (`id`, `memberId`, `repositoryId`, `githubRepositoryId`, `commitSha`, `filePath`, `lineStart`, `lineEnd`, `claimType`, `summary`). P-02 can return READY only after validating the real snapshot. Access failure returns HTTP 200 with no snapshot/protected fields and BLOCKED repository access; a different owner gets 404 before any GitHub lookup. `canInterview` and `canPrivateInterview` remain false. Web BFF validation of this Snapshot DTO is a separate follow-up; no new endpoint or P-05 is added.

Tests explicitly disable the automatic worker and real source bean. Source/analyzer tests use a loopback GitHub mock; integration tests must use a separate PostgreSQL database through `PROJECT_DB_URL`/`INTERVIEW_DB_URL`, never the development database. A source client is injected only against the mock for worker tests. Live GitHub content E2E is not part of this test suite.

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

The application accounts have no rights in other service schemas. Flyway migrations belong to the service whose schema they change. Project Service applies V1 through V8 at startup. Do not run an older A-stage Project binary after multiple repositories have been written; it does not maintain the new membership table.

## Isolated local Mailpit for DevField Auth

Run only `compose.mailpit.local.yaml` for test mail. It has its own Compose project and no persistent volume:

```powershell
docker compose -f compose.mailpit.local.yaml up -d
docker compose -f compose.mailpit.local.yaml down
```

SMTP is `127.0.0.1:11025` and the inbox is `http://127.0.0.1:18025`. Set `AUTH_SMTP_HOST=127.0.0.1` and `AUTH_SMTP_PORT=11025` in the ignored Auth local environment. These commands do not manage the PostgreSQL Compose project or other projects' containers.
