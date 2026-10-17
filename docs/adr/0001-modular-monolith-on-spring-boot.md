# ADR 0001: Modular monolith on Spring Boot

- **Status:** accepted
- **Date:** 2026-10-12
- **Deciders:** project team

## Context

Three developers build the PARI backend in one semester. The domain is strongly relational (users, spots, reservations, payments, reports) and money moves through the platform (F17-F19). The frontend is React and expects REST endpoints per screen.

## Decision

We build one Spring Boot application, deployed as a single unit, split into modules by business area (`auth`, `user`, `spot`, `availability`, `reservation`, `payment`, `review`, `support`, `verification`, `premium`, `notification`, `common`). Modules talk through public service interfaces and domain events, not through each other's internals.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Microservices | Operational overhead (deployment, tracing, distributed transactions) far exceeds what the team and project need. |
| Quarkus / Micronaut | Smaller community and less beginner material; no need for native images. |
| Jakarta EE application server | Heavier setup, slower scaffolding. |

## Consequences

Simple deployment and local development, transactions across modules stay possible. Module boundaries are only enforced by convention until we add an architecture test (for example ArchUnit); a module can be extracted into a service later because the seams already exist.
