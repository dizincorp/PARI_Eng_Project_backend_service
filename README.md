# PARI_Eng_Project_backend_service

Backend of PARI, a portal for sharing and reserving private parking spaces (engineering degree project, Lublin University of Technology). Java, Spring Boot, PostgreSQL + PostGIS.

Documentation lives in Confluence (space *My Software Team*, page *PARI project documentation*). Repository documentation, ADRs and diagrams are in [docs](docs) ([docs/diagrams](docs/diagrams)); contributor rules are in [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md). What the system does today: [backend concepts](docs/backend-concepts.md), [data layer](docs/data-layer.md) ([data dictionary](docs/data-dictionary.md)), [frontend and backend interaction](docs/frontend-backend-interaction.md), [REST API reference](docs/api-reference.md), [support, penalty, evacuation and moderation workflows](docs/support-workflows.md).

## Current state

The backend implements the whole rental flow on top of the Flyway schema: registration and login (JWT), vehicles, parking spaces with photos, weekly availability and blocked periods, map search (PostGIS), booking with server-side pricing (hourly, daily, monthly with discounts), owner approval, check-in with PIN or QR, check-out with overstay penalties, scheduled jobs (expiry of unanswered requests, forced end of forgotten sessions, payout release), soft-escrow payouts and withdrawals, reviews, notifications, live occupancy over SSE, support tickets with evacuation, assessed penalties and warnings or blocks of accounts (see [support workflows](docs/support-workflows.md)), owner and driver analytics, and Premium plans. Payments go through a **mock gateway** (no real money, see [ADR 0004](docs/adr/0004-payment-gateway-port-with-mock.md)).

Not implemented yet: refresh tokens and server-side logout, e-mail and phone verification, password reset, the inspection workflow, extending a reservation, the PDF confirmation, the real Stripe adapter, e-mail and push notifications. The full picture with diagrams is in [docs/backend-concepts.md](docs/backend-concepts.md) and [docs/data-layer.md](docs/data-layer.md).

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

The default is a **clean** database: schema and reference data only (`platform_settings`, `amenities`), no users, vehicles, spots or reservations. Everything is created through the API. A demo data set is optional. Each variant has its own Docker volume, so they never mix:

| Variant | What is in it | Start the database | Start the backend |
| --- | --- | --- | --- |
| **clean** (default) | schema and reference data | `docker compose up -d` (port 5432) | `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` |
| **seeded** (optional) | the same plus a consistent demo data set (below) | `docker compose --profile seed up -d postgres-seeded` (port 5433) | `SPRING_PROFILES_ACTIVE=local,seed ./gradlew bootRun` |

On PowerShell set the profile first: `$env:SPRING_PROFILES_ACTIVE = "local"` (or `"local,seed"`), then `./gradlew bootRun`.

The variant is decided by the Flyway locations: the migrations in `db/migration` always run; `db/dev` (the demo data) only when it is listed in `spring.flyway.locations`, which only the `seed` profile does. Elsewhere set `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/dev` to load it; never do that in production. A database keeps the variant it was created with: Flyway refuses to start a seeded database without `db/dev`, so switch variants by using the other database, or recreate one with `docker compose down -v`.

### The demo data

`src/main/resources/db/dev/R__demo_data.sql` is a repeatable migration, so it always runs after the versioned ones and a new `V7` never conflicts with it. It runs once, on an empty database, and does nothing when the demo users exist. After changing the file, recreate the database. It contains:

- Users (password `Demo1234!`): `anna.owner@pari.test` (owner), `jan.driver@pari.test` (driver), `ola.both@pari.test` (owner with an active Premium plan, also a driver), `vera.verifier@pari.test` (verifier). Fixed ids are listed at the top of the file.
- Two vehicles, two cards (book with token `pm_card_visa`; `pm_card_chargeDeclined` is declined by the mock gateway), five spots in Lublin (four active, one draft) with amenities, weekly availability and one blocked day, verification requests (approved, queued, rejected).
- Eleven reservations, one per state: three completed and reviewed (one with an overstay penalty), one completed and not reviewed yet, one in progress, one confirmed, one waiting for the owner, and cancelled, rejected, expired and payment-declined ones, with their charges, refunds, penalty and payouts (paid, scheduled, cancelled), reviews, notifications and the spot ratings.
- All amounts follow `PricingService` and `platform_settings`, and `DemoDataTest` checks the consistency (money, payment and payout states, ratings, verification, occupancy). Times are relative to the day the database is created. The request waiting for the owner expires like any other after `approval_timeout_minutes`.
- Not seeded: phone numbers, withdrawals (both are encrypted with the application key) and spot photos (they need files in the media directory).

### Scenario seed (Java, relative to now)

For a Lublin data set with drivers, owners, an ADMIN and a SUPPORT account, listings (one with three spaces), blocks, reservations in every state, an overdue session ended by the real auto-check-out, and support tickets, start a **clean** database and run `SPRING_PROFILES_ACTIVE=local,scenario-seed ./gradlew bootRun`. It is idempotent, only runs on `local` or `test` against a database on this machine, and is documented in [docs/seed-data.md](docs/seed-data.md) (accounts, scenarios, passwords, how to switch it off).

Secrets come from environment variables: `PARI_DATA_KEY` (data encryption) and `PARI_JWT_SECRET` (token signing), each the base64 of at least 32 random bytes; the application refuses to start without them. The `local` profile contains development-only values for both, and also accepts the legacy `X-User-Id` header (`pari.security.jwt.dev-header-auth`) so scripts keep working; never enable that outside development.

**Development staff accounts (ADMIN and SUPPORT).** Public registration never grants staff roles. To try the support workflows on a clean database, start the app with the `local` profile and set the passwords in your environment (no defaults exist, nothing is committed):

```bash
export PARI_DEV_ADMIN_PASSWORD='choose-a-password'      # 8 to 72 characters
export PARI_DEV_SUPPORT_PASSWORD='choose-another-one'
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

On PowerShell use `$env:PARI_DEV_ADMIN_PASSWORD = "..."`. At start-up the app creates `admin@pari.test` (role `ADMIN`) and `support@pari.test` (role `SUPPORT`); change the e-mails with `pari.dev.staff.admin-email` and `pari.dev.staff.support-email`. An account whose password is not set is skipped, an existing account is never changed, and running it again creates nothing. Log in with `POST /api/auth/login` as usual. This runs only under the `local` profile, so it never runs in production.

The `local` profile contains a development-only encryption key. In every other environment set `PARI_DATA_KEY` (base64 of 32 random bytes, for example `openssl rand -base64 32`) and `PARI_DB_URL`, `PARI_DB_USER`, `PARI_DB_PASSWORD`.

## API documentation (Swagger UI)

With the app running, open <http://localhost:8080/swagger-ui.html> to browse and try every endpoint. The raw OpenAPI document is at `/v3/api-docs`. Log in with `POST /api/auth/login` (register first on a clean database; on the seeded one use `jan.driver@pari.test` / `Demo1234!`), copy the `token` from the answer and paste it into **Authorize**.

## Workflow

Contributors work through pull requests: create a branch, push it, open a PR, get a code owner's approval, squash merge. GitHub blocks direct pushes to `main` for everyone except the repository owner. AI agents always open a PR. Refer to requirement ids (F17, NF04, ...) in commits and PRs. Details: [CONTRIBUTING.md](CONTRIBUTING.md); AI agents also read [AGENTS.md](AGENTS.md).
