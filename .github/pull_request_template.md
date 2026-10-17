## What & why
<!-- Short description of the change and the motivation. Link the Jira task (PARI-123) and requirement ids (F17, NF04). -->

## How to test
<!-- Steps or commands a reviewer can use to verify -->

## Assumptions, placeholders, not done
<!-- Anything you assumed, any placeholder business value you introduced, anything left out on purpose -->

## Checklist
- [ ] PR title follows `type(scope): subject` (it becomes the squash commit)
- [ ] One concern per PR; branch is up to date with `main`
- [ ] `./gradlew build` passes locally (Docker running for the repository tests)
- [ ] Tests added or updated; a bug fix has a test that fails without the fix
- [ ] Database change = new Flyway migration, no edit of an existing one
- [ ] Docs updated in this PR (Confluence / `docs/` / diagrams) or not needed
- [ ] No secrets, personal data, `.idea/`, build output or generated files
