# ADR 0003: Gradle with the Groovy DSL, Java 21

- **Status:** accepted
- **Date:** 2026-10-17
- **Deciders:** project team

## Context

The stack page proposed Java 21 LTS (Java 25 acceptable) and left the build tool open between Maven and Gradle. The team chose Gradle with the Groovy DSL.

## Decision

- Build with Gradle, Groovy DSL (`build.gradle`, `settings.gradle`), using the committed Gradle wrapper.
- Java 21 LTS through the Gradle toolchain (`languageVersion = 21`), so every developer and CI compile with the same language level whatever JDK runs Gradle.
- Spring Boot dependency versions come from the Spring Boot BOM; do not pin versions of managed libraries.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Maven | Team preference for Gradle's concise build scripts. |
| Gradle Kotlin DSL | Team preference for Groovy. |
| Java 25 | Newer LTS, but 21 is the version the team and CI standardised on; revisit when Spring Boot and all tools have settled on 25. |

## Consequences

A JDK 21 must be installed (IntelliJ can install one). CI uses the same version. Upgrading Java is a one-line change plus a CI change, done in its own pull request.
