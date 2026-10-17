# AGENTS.md

Instructions for AI coding agents (Claude Code, Codex, Copilot, Cursor, Gemini and similar) working in this repository. Humans should read [CONTRIBUTING.md](CONTRIBUTING.md); this file is the short, operational version of the same rules plus what an agent needs to avoid mistakes.

## Project

PARI is a portal where private owners rent out parking spaces and drivers find, reserve, pay for and check in to them. This repository is the **backend service** of an engineering degree project (Lublin University of Technology, team of three). Product requirements are identified as F01-F25 and NF01-NF15 and live in the Confluence space *My Software Team* (page *PARI project documentation*). The Polish design document is authoritative; Confluence pages are the working copy.

**Current state:** the data layer exists (Flyway schema, JPA entities, repositories, tests). There is **no REST API, security or payment integration yet**; they are planned (Spring Web MVC, Spring Security with JWT, Stripe, S3-compatible storage, SSE for live spot status). Do not describe planned parts as existing.

## Stack

Java 21, Gradle (Groovy DSL, wrapper committed), Spring Boot 4.x, Spring Data JPA / Hibernate, PostgreSQL + PostGIS, Flyway, Lombok, JUnit 5 + AssertJ + Testcontainers.

## Commands

```bash
./gradlew build                    # compile + all tests
./gradlew test                     # all tests
./gradlew test --tests '*ReservationTest'   # one class
docker compose up -d               # local PostgreSQL + PostGIS
```

- Unit tests (domain rules, value objects, encryption) need nothing else.
- Repository tests (`DbTestBase` subclasses) start PostgreSQL + PostGIS with Testcontainers and **need a running Docker daemon**. If Docker is unavailable, say so explicitly; never report those tests as passing when they did not run.
- Run `./gradlew test` before saying work is done. Report failures with the real output.

## Layout

```
src/main/java/pl/pollub/pari/
  common/        BaseEntity, Money, TimeRange, IllegalStateTransitionException, encryption, audit log, platform settings
  auth/ user/ spot/ availability/ reservation/ payment/ review/ support/ verification/ premium/ notification/
    domain/      JPA entities, enums, embeddables (business rules of one object live here)
    repository/  Spring Data repositories
src/main/resources/db/migration/   Flyway migrations (V1__init_schema.sql, ...)
src/test/java/                     mirrors main; support/DbTestBase is the base for DB tests
docs/                              repo documentation (see docs/README.md), docs/diagrams (Mermaid), docs/adr (decisions)
```

New code goes into the module that owns the concept. Do not create a module for one class; do not put business code in `common`.

## Rules that are easy to break

**Database**
- The schema is owned by Flyway; Hibernate only validates it (`ddl-auto=validate`). Any entity change that touches columns needs a **new** migration `V<next>__<description>.sql`.
- **Never edit a migration that is already on `main`.** Fix forward with a new one.
- Enums are stored as strings (`@Enumerated(EnumType.STRING)`) with a `CHECK` constraint in the database; adding a value needs both a Java and a migration change.
- `ReservationStatus.SLOT_HOLDING`, the `reservations_no_overlap` exclusion constraint and the native search query in `ParkingSpotRepository` must stay in sync. Change one, change all three and the tests.
- `audit_log` is append-only (trigger). Never add update or delete paths for it.
- PostgreSQL cannot infer the type of `? is null` for timestamps. For optional filters use a JPA `Specification` (see `ReservationRepository.findForOwner`), not `(:x is null or ...)`.

**Domain**
- Entities extend `BaseEntity`, have a `protected` no-arg constructor, use `@Getter`, and **no setters for state**. State changes go through methods that throw `IllegalStateTransitionException` for illegal transitions.
- Associations are `LAZY`. Never return entities from a future controller; use DTOs.
- Money is `Money` (long minor units, PLN). Never use `double`/`float` for money. Business values (commission, fees, penalty rates) come from `platform_settings`, not from constants. **Do not invent business values**; if one is missing, use a clearly marked placeholder in the migration and say so in the PR.
- Time is `Instant` in UTC. Domain methods take `Instant now` as a parameter; services will inject `java.time.Clock`. Do not call `Instant.now()` inside domain logic.
- Personal data (phone, later others) uses `EncryptedStringConverter`. Never log personal data, tokens or keys.

**Security and secrets**
- No secrets in the repository. Keys come from environment variables (`PARI_DATA_KEY`, `PARI_DB_*`). The keys in `application-local.yml` and test resources are dev/test-only on purpose.
- Do not weaken checks (constraints, validations, `ddl-auto=validate`) to make a test pass.

**Dependencies and scope**
- Do not add a dependency without saying why in the PR. Prefer what Spring Boot already manages.
- Keep changes minimal and focused: one concern per branch. No drive-by refactors, reformatting of untouched files, or renamed packages.

## Tests

- Every behaviour change or bug fix comes with a test. Prefer unit tests for domain rules; use `DbTestBase` when the database matters (constraints, queries, mappings).
- Test names describe behaviour (`cancelIsPossibleBeforeCheckInOnly`), not methods. Use AssertJ.
- Tests must not depend on order, wall-clock time or leftover data. DB tests roll back automatically.

## Git workflow

- **Every change reaches `main` through a pull request, whoever makes it: human or AI agent.** Nobody commits or pushes directly to `main`. GitHub currently blocks only deleting `main` and force-pushing to it; it does not block direct pushes, so this rule depends on discipline. Do not use that gap, not even for a one-line documentation fix.
- Work on a branch, push it, open a PR into `main`, squash merge.
- Branch names: `feature/<short-desc>`, `fix/<short-desc>`, `docs/<short-desc>`, `chore/<short-desc>` (optionally with the Jira key: `feature/PARI-123-short-desc`).
- Commit messages: `type(scope): subject` (types: feat, fix, docs, test, refactor, build, chore), imperative, under 72 characters, body explains why. Reference requirement ids (F17, NF04) and the Jira key when relevant.
- **Agents work autonomously inside the task they were given.** Create the branch, implement the change, write the tests, run `./gradlew build`, push, and open the PR with a complete description (what and why, how it was tested, assumptions and placeholders, what was not done). Do not stop to ask permission for each of these steps.
- **Merging:** an agent merges its own PR only when the person who gave the task said so for that task, and only after CI is green (or after stating exactly which check could not run and why). Use squash merge. Without that instruction, leave the PR open for a human.
- **Agents must not**: push to `main`, force-push or rewrite published history, change rulesets, branch protection or `.github/CODEOWNERS`, reopen or delete pull requests and branches that someone else closed, or bypass hooks and checks. If a PR was closed by a human, ask before opening another one for the same work.
- Never commit `.idea/`, `build/`, `.gradle/`, `.env` files or generated artifacts.

## Documentation

- Behaviour or design changes update the docs in the same PR: Confluence for team documents, `docs/` for repo-level documents and diagrams. See [docs/README.md](docs/README.md).
- Architectural decisions are recorded as short ADRs in `docs/adr/`.
- Write English. Keep documentation factual and say what is not implemented yet.

## When unsure

Ask a human when a requirement is ambiguous, a business value is missing, or an action is hard to undo (deleting data, changing a migration already merged, changing repository settings). Otherwise decide, proceed, and state the assumptions in the PR description.
