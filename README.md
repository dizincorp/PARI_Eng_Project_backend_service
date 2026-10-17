# PARI_Eng_Project_backend_service

Backend of PARI, a portal for sharing and reserving private parking spaces (engineering degree project, Lublin University of Technology). Java, Spring Boot, PostgreSQL + PostGIS.

Documentation lives in Confluence (space *My Software Team*, page *PARI project documentation*). Diagrams are in [docs/diagrams](docs/diagrams) once merged.

## Current state

The data layer is implemented: Flyway schema, JPA entities with their business rules, Spring Data repositories and tests. There is no REST API yet.

| Part | Where |
| --- | --- |
| Schema (Flyway) | `src/main/resources/db/migration/V1__init_schema.sql` |
| Entities and repositories, one package per module | `src/main/java/pl/pollub/pari/<module>/{domain,repository}` |
| Shared value objects, encryption, audit log | `src/main/java/pl/pollub/pari/common` |
| Tests | `src/test/java` |

## Requirements

- JDK 21 (the Gradle toolchain is set to 21; Gradle finds an installed JDK 21 automatically, for example one installed by IntelliJ IDEA)
- Docker (tests start PostgreSQL + PostGIS with Testcontainers; local development uses `docker-compose.yml`)

## Run the tests

```bash
./gradlew test
```

Unit tests of the domain rules run without Docker; the repository tests need a running Docker daemon.

## Run against a local database

```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

The `local` profile contains a development-only encryption key. In every other environment set `PARI_DATA_KEY` (base64 of 32 random bytes, for example `openssl rand -base64 32`) and `PARI_DB_URL`, `PARI_DB_USER`, `PARI_DB_PASSWORD`.

## Workflow

Every change goes through a pull request, whoever makes it (team member or AI agent); nobody commits to `main` directly. Create a branch, push it, open a PR, get a review, squash merge. Refer to requirement ids (F17, NF04, ...) in commits and PRs. Details: [CONTRIBUTING.md](CONTRIBUTING.md); AI agents also read [AGENTS.md](AGENTS.md).
