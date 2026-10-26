# RepoQ

Gradle multi-project for independent Project and Interview Spring Boot services. Phase 1 exposes health only; Project, Interview, GitHub analysis and AI APIs are not implemented.

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

The application accounts have no rights in other service schemas. Flyway migrations belong to the service whose schema they change. This Phase has no domain migrations or tables.
