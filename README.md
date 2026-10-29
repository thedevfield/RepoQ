# RepoQ

Gradle multi-project for independent Project and Interview Spring Boot services. Project Service A stage supports multiple public GitHub repositories per project with owner-scoped P-01/P-02/P-03/P-07/P-08. Private GitHub App access and consent are the next development unit. Initial analysis remains queued; no worker, Snapshot, Interview domain, or AI API is implemented.

Project API startup requires `PROJECT_AUTH_ISSUER` and `PROJECT_AUTH_AUDIENCE` matching DevField-Auth, `PROJECT_AUTH_JWKS_URI`, `PROJECT_AUTH_SESSION_CHECK_URI`, and the raw Project-only `PROJECT_AUTH_SERVICE_KEY`. Copy the names from `.env.example` into the ignored `.env.local`; do not commit secrets. `PROJECT_GITHUB_TOKEN` is optional for public repository metadata but is recommended when unauthenticated GitHub API rate limits are insufficient. All Project API calls require a Bearer access token. Project verifies the JWT locally, then calls Auth's session check with its service credential on each protected request. Auth unavailability blocks those requests.

`POST /api/projects` accepts either the existing `{ "repositoryUrl": "https://github.com/owner/repo" }` or `{ "name": "Project", "repositories": [{ "repositoryUrl": "https://github.com/owner/one" }, { "repositoryUrl": "https://github.com/owner/two" }] }`. It returns HTTP 201 with `id`, `analysisStatus: QUEUED`, and repository details. `GET /api/projects` returns only the caller's project summaries; `GET /api/projects/{id}` includes per-repository and overall queued status with `canInterview: false` and `latestReadySnapshot: null`. `POST /api/projects/{id}/repositories` adds public repositories and `DELETE /api/projects/{id}/repositories/{memberId}` removes one, retaining at least one member. Private IDs, Private URLs, and consent payloads remain rejected during the GitHub App connection stage. All writes use the Project write fences and database uniqueness; V2 backfills existing public registrations, V3 retires the legacy project-level uniqueness constraint, and V4 adds GitHub App connection state without changing V1 through V3. The [Web BFF contract](docs/github-app-bff-contract.md) describes P-09 through P-11. This stage does not process jobs or collect repository files.

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

The application accounts have no rights in other service schemas. Flyway migrations belong to the service whose schema they change. Project Service applies V1 through V3 at startup. Do not run an older A-stage Project binary after multiple repositories have been written; it does not maintain the new membership table.

## Isolated local Mailpit for DevField Auth

Run only `compose.mailpit.local.yaml` for test mail. It has its own Compose project and no persistent volume:

```powershell
docker compose -f compose.mailpit.local.yaml up -d
docker compose -f compose.mailpit.local.yaml down
```

SMTP is `127.0.0.1:11025` and the inbox is `http://127.0.0.1:18025`. Set `AUTH_SMTP_HOST=127.0.0.1` and `AUTH_SMTP_PORT=11025` in the ignored Auth local environment. These commands do not manage the PostgreSQL Compose project or other projects' containers.
