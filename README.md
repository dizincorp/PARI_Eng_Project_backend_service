# PARI_Eng_Project_backend_service

Backend of PARI, a portal for sharing and reserving private parking spaces (engineering degree project, Lublin University of Technology). Java, Spring Boot, PostgreSQL + PostGIS.

Documentation lives in Confluence (space *My Software Team*, page *PARI project documentation*). Repository documentation, ADRs and diagrams are in [docs](docs) ([docs/diagrams](docs/diagrams)); contributor rules are in [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

## Current state

The data layer is implemented (Flyway schema, JPA entities with their business rules, repositories), and the main flow runs on top of it: **creating a parking space, searching for one, reserving it and paying for it**. Payments go through a **mock gateway** (no real money, see [ADR 0004](docs/adr/0004-payment-gateway-port-with-mock.md)).

Not implemented yet: authentication (callers are identified by a temporary `X-User-Id` header, never expose this outside development), check-in and check-out, overstay penalties, the payout release job, reviews, support, verification workflow, notifications, premium, and the real Stripe adapter.

| Part | Where |
| --- | --- |
| Schema (Flyway) | `src/main/resources/db/migration/V1__init_schema.sql` |
| Entities and repositories, one package per module | `src/main/java/pl/pollub/pari/<module>/{domain,repository}` |
| Services (business flow) and REST controllers | `src/main/java/pl/pollub/pari/<module>/{service,web}` |
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

The `local` profile also loads demo data (`src/main/resources/db/dev`): an owner, a driver, a vehicle, a test card and four active spots in Lublin. The ids are listed at the top of `V1000__demo_data.sql` (for example driver `00000000-0000-0000-0000-000000000002` for `X-User-Id`; book with token `pm_card_visa`). To load it elsewhere set `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev`; never do this in production.

The `local` profile contains a development-only encryption key. In every other environment set `PARI_DATA_KEY` (base64 of 32 random bytes, for example `openssl rand -base64 32`) and `PARI_DB_URL`, `PARI_DB_USER`, `PARI_DB_PASSWORD`.

## API documentation (Swagger UI)

With the app running, open <http://localhost:8080/swagger-ui.html> to browse and try every endpoint. The raw OpenAPI document is at `/v3/api-docs`. There is no real authentication yet: click **Authorize** and enter a user UUID, which is sent as the temporary `X-User-Id` header.

## Workflow

Contributors work through pull requests: create a branch, push it, open a PR, get a code owner's approval, squash merge. GitHub blocks direct pushes to `main` for everyone except the repository owner. AI agents always open a PR. Refer to requirement ids (F17, NF04, ...) in commits and PRs. Details: [CONTRIBUTING.md](CONTRIBUTING.md); AI agents also read [AGENTS.md](AGENTS.md).
