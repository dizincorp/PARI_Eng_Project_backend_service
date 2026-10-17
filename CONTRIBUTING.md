# Contributing

Rules for everyone who changes this repository, humans and AI agents alike. AI agents also read [AGENTS.md](AGENTS.md), which is the condensed, technical version of this file.

## 1. Getting started

1. Install JDK 21 and Docker. Clone the repository.
2. Run the checks once to see that your setup works:

   ```bash
   ./gradlew build
   ```

   Repository tests start PostgreSQL + PostGIS with Testcontainers, so Docker must be running.
3. For manual work against a database: `docker compose up -d`, then `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`. Details are in the [README](README.md).
4. IntelliJ IDEA: import the project as a Gradle project, enable annotation processing (Lombok), and install the *Mermaid* plugin to preview `docs/diagrams`. The `.editorconfig` sets the formatting.

## 2. The flow: branch, pull request, review, merge

**Every contributor makes a pull request: team members, maintainers and AI coding agents alike.** Nobody commits or pushes directly to `main`, and there are no exceptions for small or documentation-only changes.

GitHub currently blocks only deleting `main` and force-pushing to it (ruleset *main restrictions*). It no longer blocks direct pushes or requires approvals, so the pull request rule is a team agreement that everyone must keep. If the team wants it enforced again, re-enable "Require a pull request before merging" in the ruleset.

1. **Pick a task** from the Jira board (project key `PARI`). Titles follow `[Part] Current task or problem`, for example `[Backend/Auth] Add refresh token rotation`.
2. **Branch from an up-to-date `main`**: `git switch main && git pull && git switch -c feature/PARI-123-short-desc`.
3. **Commit** in small logical steps (section 3).
4. **Push and open a pull request** into `main`. Fill in the PR template. One concern per PR; keep it small enough to review in 20 minutes.
5. **CI must be green** (build and tests).
6. **Get a review.** A teammate (ideally a code owner, see `.github/CODEOWNERS`) reads the diff, and all review conversations are resolved. When no other reviewer is available, the author may merge their own PR after re-reading the whole diff once more as a reviewer would.
7. **Squash and merge.** Use squash merge only, so `main` has one commit per PR with the PR title as its message. Delete the branch afterwards.

Draft PRs are welcome for early feedback.

## 3. Branches and commits

| Branch prefix | Use |
| --- | --- |
| `feature/` | new behaviour |
| `fix/` | bug fix |
| `docs/` | documentation and diagrams only |
| `chore/` | build, CI, repository settings, dependencies |
| `refactor/` | behaviour-preserving restructuring |

Add the Jira key when there is one: `feature/PARI-123-short-desc`. Use lowercase and hyphens.

Commit messages follow Conventional Commits: `type(scope): subject`.

- Types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `chore`.
- Scope is the module or area: `reservation`, `spot`, `db`, `ci`.
- Subject: imperative mood, no full stop, under 72 characters ("add payout release query").
- Body (optional): why the change is needed, not what the diff shows.
- Refer to requirement ids and the Jira key when relevant: `Refs: F17, PARI-123`.

Because PRs are squash-merged, the **PR title** becomes the commit on `main`: write it in the same format.

## 4. Pull request checklist

The PR template contains this list; do not tick what you did not do.

- The PR does one thing and says why (requirement ids, Jira link).
- `./gradlew build` passes locally.
- New or changed behaviour has tests; bug fixes have a test that fails without the fix.
- Database changes come with a new Flyway migration and no edit of an existing one.
- No secrets, personal data, `.idea/`, build output or generated files.
- Documentation is updated in the same PR (section 6).
- Anything unfinished, assumed or deliberately left out is written in the PR description.

## 5. Reviewing

Reviewers check, in this order: correctness and requirements, tests, data safety (migrations, constraints, money, personal data), security, readability, then style. Be specific and kind; suggest the change you want. Approve only what you have understood. Small follow-ups can be separate issues; blockers must be fixed before merge.

Code owners are listed in [.github/CODEOWNERS](.github/CODEOWNERS). When someone joins the team, add them there (ideally through a GitHub team).

## 6. Documentation rules

See [docs/README.md](docs/README.md). In short: the Polish design document is authoritative, Confluence holds the team documents, `docs/` holds repo-level documents and diagrams, and every behaviour or design change updates the matching document in the same PR.

## 7. Code rules

- Follow the existing structure: one package per module with `domain` and `repository` (and later `web`, `service`).
- Business rules of one object live in its entity; rules across objects will live in services.
- Money is `Money` in minor units; time is `Instant` in UTC; enums are stored as strings.
- Personal data is encrypted at rest and never logged.
- Prefer clear code over comments; comment the *why* when it is not obvious (for example a business rule or a PostgreSQL limitation).
- Do not add dependencies without discussing them in the PR.

## 8. Secrets and security

- Never commit passwords, API keys, tokens or real personal data. Use environment variables (`PARI_DATA_KEY`, `PARI_DB_URL`, `PARI_DB_USER`, `PARI_DB_PASSWORD`).
- The keys in `application-local.yml` and `src/test/resources` are for development and tests only.
- If a secret was committed by mistake, tell the code owners immediately; the secret must be rotated, deleting the commit is not enough.
- Report a suspected vulnerability to the code owners privately, not in a public issue.

## 9. Working with AI coding agents

Agents follow [AGENTS.md](AGENTS.md). In short:

- Agents may implement a task on their own: branch, code, tests, `./gradlew build`, push, and a complete pull request description.
- **Agents make pull requests like everyone else.** They never push to `main`.
- An agent merges its own PR only if the person who gave the task said so, and only after CI is green.
- Agents do not change repository settings, add dependencies silently, invent business values, or reopen what a human closed.
- The person who asked for the change is responsible for it: review AI-written code as carefully as any other code, especially migrations, money and security.
