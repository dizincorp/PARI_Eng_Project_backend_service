# ADR 0002: PostgreSQL with PostGIS, schema owned by Flyway

- **Status:** accepted
- **Date:** 2026-10-17
- **Deciders:** project team

## Context

A space must never be booked twice for overlapping times (F13). Drivers search spaces on a map by distance, price and free time (F11, F12, NF02). Reservation and payment data need transactions and constraints. Audit records must be immutable (NF05).

## Decision

- PostgreSQL 16 with the PostGIS and `btree_gist` extensions is the only database.
- The schema is created and changed only by Flyway migrations in `src/main/resources/db/migration`. Hibernate runs with `ddl-auto=validate`.
- Business invariants are enforced in the database as well as in code: an exclusion constraint on `reservations` prevents overlapping slot-holding reservations, `CHECK` constraints back every enum, a trigger makes `audit_log` append-only.
- Spots store `lat`/`lng`; a generated `geography` column with a GiST index serves `ST_DWithin` searches.
- Money is stored as `bigint` minor units; the platform currency is PLN.
- Tests run against the same engine (Testcontainers with a PostGIS image), never an in-memory substitute.

## Alternatives considered

| Option | Why not |
| --- | --- |
| MySQL | No exclusion constraints, weaker geo support. |
| MongoDB | Reservations and money need transactions and relational constraints. |
| H2 for tests | Does not support PostGIS or exclusion constraints, so tests would not prove the real behaviour. |
| Hibernate-generated schema | Unreviewable changes and no history; Flyway migrations are reviewed in pull requests. |

## Consequences

Repository tests need Docker. Migrations already on `main` are immutable; fixes go forward as new versions. Some PostgreSQL details leak into code, for example the need to avoid `? is null` for timestamp parameters, which is why optional filters use JPA Specifications.
