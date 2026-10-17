@AGENTS.md

# Claude Code notes

- The shared rules for every agent are in `AGENTS.md` (imported above). Keep them there; add only Claude-specific details here.
- Use the Gradle wrapper (`./gradlew`), never a globally installed Gradle. On Windows PowerShell use `.\gradlew.bat`.
- Docker Desktop must be running for the repository tests. If it is not, report that instead of skipping silently.
- Attribution: end commit messages with the `Co-Authored-By` line configured for your session.
