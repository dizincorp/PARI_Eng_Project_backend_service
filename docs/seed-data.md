# Scenario seed data (PARI-94)

A repeatable, Lublin-focused data set for trying the reservation, support and map workflows without creating every record by
hand. It is for **local development and tests only**. It is separate from the SQL demo data of the `seed` profile
([README](../README.md#the-demo-data)): that one is loaded once by Flyway into its own database, this one is created by Java code
through the real services, relative to the current time, and can be started on the clean database.

## Start it

1. A clean local database: `docker compose up -d` (port 5432).
2. Start the backend with the profile `scenario-seed` next to `local`:

```bash
SPRING_PROFILES_ACTIVE=local,scenario-seed ./gradlew bootRun
```

On PowerShell: `$env:SPRING_PROFILES_ACTIVE = "local,scenario-seed"`, then `./gradlew bootRun`.

The seed runs once at start-up, in one transaction, and logs how many accounts, listings and reservations it created. Log in with `POST /api/auth/login` (or the Swagger UI) as any account below.

**Passwords.** Set `PARI_SEED_PASSWORD` for the driver and owner accounts, and `PARI_SEED_ADMIN_PASSWORD` and
`PARI_SEED_SUPPORT_PASSWORD` for the staff (each 8 to 72 characters; the staff passwords fall back to the first one). When none is
set, the local default is **`Seed1234!`** for every account. It is documented on purpose, it is not a secret, and the guard (below)
means it can only end up in a local or test database. Public registration never grants staff roles: the staff accounts exist only
because of this seed (and `DevStaffSeeder`, which creates `admin@pari.test` and `support@pari.test` under the `local` profile when
its own passwords are set).

## Safety

- It runs only when `pari.seed.enabled=true`, which only the `scenario-seed` profile sets. **To switch it off, do not use the
  profile** (or set `pari.seed.enabled=false`).
- `SeedGuard` stops the application from starting, with a message, unless the profile `local` or `test` is active, no
  production-like profile (`prod`, `production`, `staging`, `live`) is active, and the database host is `localhost`, `127.0.0.1`,
  `::1` or a service name of `docker-compose.yml` (`postgres`, `postgres-seeded`). A remote database is refused even under
  `local`.
- It never deletes or updates anything and never truncates a table. It only adds what is missing.

## Run it again

Idempotent: accounts are found by e-mail, vehicles by plate, listings by name and owner, reservations by their fixed code
(`SEED-...`). A second run creates nothing new and changes nothing. Because it does not move existing records, the dates of an
existing data set stay where they were: after some days the "upcoming" reservations are in the past. To get a fresh set relative to
today, recreate the database (`docker compose down -v`, then start it again). There is no separate reset command: deleting by tag
would have to cross about fifteen tables and could damage data that testers created.

## Accounts

All e-mails end in `@seed.pari.test`.

| Account | Roles | Use it for |
|---|---|---|
| `jan.kowalski` | DRIVER | the driver with the active session, the driver ticket and the upcoming reservation |
| `maria.nowak` | DRIVER | the review, the cancelled, pending and overdue reservations |
| `anna.wisniewska` | OWNER | the listing with three spaces and the central garage, blocks, owner calendar |
| `piotr.zielinski` | OWNER | manual approval (pending and expired requests), the listing without reservations |
| `ola.lewandowska` | DRIVER, OWNER | both roles: the weekday-only listing, and a driver whose session is overdue |
| `marek.kaminski` | DRIVER, OWNER | both roles: the campus listing, and a driver whose card is declined |
| `admin` | ADMIN | staff workflows |
| `support` | SUPPORT | the ticket queue and the support commands |

Vehicles (distinct plates): Jan `LU SD101`, `LU SD102`; Maria `LU SD201`, `LU SD202`; Ola `LU SD301`; Marek `LU SD401`. Every driver
has a saved default card that the mock gateway accepts, **except Marek**, whose saved default card is the declined one
(`pm_card_chargeDeclined`): he books with a different token, and a penalty charged to his default card fails.

## Listings (all in Lublin)

| Listing | Owner | Spaces | Hourly / daily / monthly (PLN, sample values) | Booking | Weekly availability |
|---|---|---|---|---|---|
| Rynek - garaż podziemny | Anna | 1 | 12 / 80 / 700 | instant | every day 06-22 |
| Plac Litewski - trzy miejsca | Anna | **3** (`#1`-`#3`, booked independently) | 8 / 60 / 500 | instant | every day 06-22 |
| Lublin Plaza - podjazd | Piotr | 1 | 6 / 40 / 350 | **owner approves** | every day 06-22 |
| Bronowice - miejsce przy domu | Ola | 1, gate PIN set | 4 / 30 / 250 | instant | Monday-Friday 07-19 |
| UMCS - parking kampusowy | Marek | 1 | 5 / 35 / 280 | instant | Monday-Friday 06-22, Saturday 08-14 |
| Czechów - garaż | Piotr | 1, no reservations | 7 / 50 / 420 | instant | every day 06-22 |

The prices are sample test values, not business values. Amenities and access instructions differ per listing.

## Availability scenarios

Times are local (Europe/Warsaw), counted from today. They are blocks created through the real service.

| Listing | Block | Meaning |
|---|---|---|
| Rynek | day +3, whole day | whole-day block |
| Rynek | day +1, 13:00-15:00 | partial block; **day +1, 15:00-20:00 is a free interval**; day +1, 09:00-11:00 is occupied by `SEED-UPCOMING` |
| Plac Litewski | day +2, 08:00-18:00, space `#3` only | a block of one space: `#1` and `#2` stay bookable |
| Plac Litewski | day +5, whole day | closure of the whole listing |
| Lublin Plaza | day +2, 14:00-16:00 | partial block next to the pending request |
| Bronowice | day +6, whole day | whole-day block |
| UMCS | day +1, 06:00-09:00 | early-morning block |

To check that a block cannot overlap a reservation, try `POST /api/spots/{id}/blocked-periods` for Rynek on day +1, 10:00-11:00:
the answer is `409 PERIOD_HAS_RESERVATIONS`.

## Reservations

Created from the clock at start-up. "Now" is the current minute.

| Code | Listing (space) | Driver | Window | State after seeding | What it is for |
|---|---|---|---|---|---|
| `SEED-UPCOMING` | Rynek | Jan | day +1, 09:00-11:00 | `CONFIRMED` | upcoming booking; also has an `OTHER` ticket from Jan |
| `SEED-APPROACHING` | Plac Litewski (#1) | Maria | now +30 min to +150 min | `CONFIRMED` | start approaching: check-in opens 5 minutes before |
| `SEED-ACTIVE` | Bronowice | Jan | now -40 min to +80 min | `IN_PROGRESS`, checked in | running session; Jan's open `CANNOT_LEAVE` ticket |
| `SEED-OVERDUE-AUTO` | Plac Litewski (#2) | Maria | now -5 h to -3 h | `COMPLETED`, auto-checked-out | see below |
| `SEED-OVERDUE-FAILED` | Rynek | Marek | now -6 h to -4 h | `COMPLETED`, auto-checked-out | see below, penalty payment fails |
| `SEED-OVERDUE-TICKET` | Lublin Plaza | Ola | now -165 min to -45 min | `IN_PROGRESS` | overdue but not yet ended: the system `VEHICLE_NOT_LEFT` ticket is open |
| `SEED-DONE-REVIEWED` | Rynek | Maria | yesterday, 10:00-12:00 | `COMPLETED` | check-in, check-out, 5-star review |
| `SEED-DONE-VNL` | UMCS | Jan | yesterday, 14:00-16:00 | `COMPLETED` | ended; Marek's `VEHICLE_NOT_LEFT` ticket (allowed since PARI-93) |
| `SEED-NO-SHOW` | UMCS | Jan | now -5 h to -3 h | `NO_SHOW` | marked by the real no-show logic |
| `SEED-CANCELLED` | Rynek | Maria | day +4, 10:00-12:00 | `CANCELLED` | cancelled by the driver, refunded |
| `SEED-EXPIRED` | Lublin Plaza | Jan | day +3, 10:00-12:00 | `EXPIRED` | the owner never answered, refunded |
| `SEED-PENDING` | Lublin Plaza | Maria | day +2, 10:00-12:00 | `PENDING_APPROVAL` | Piotr can accept or reject it |
| `SEED-SPACE-1`, `SEED-SPACE-2` | Plac Litewski (#1, #2) | Jan, Maria | day +1, 09:00-11:00 | `CONFIRMED` | two spaces of one listing booked for the same time |

**The overdue sessions are not faked.** They are created `IN_PROGRESS` with a reserved end more than the auto-check-out delay
(`auto_checkout_after_end_minutes`, 60) in the past, and then ended by the real automatic check-out (`OverdueSessionService`, the
same code the scheduled job runs). The result is what the application produces: status `COMPLETED` with the auto-checked-out
marker, the overstay penalty (after the 15-minute grace) added to the final amount, the space freed, an audit entry
(`AUTO_CHECKED_OUT`) and notifications. For `SEED-OVERDUE-AUTO` the penalty payment succeeds; for `SEED-OVERDUE-FAILED` it is recorded
as `FAILED` because Marek's saved card is declined (the check-out itself is not blocked).

Every booking was paid through the mock gateway and has its payout scheduled. A reservation in the past cannot be created through
the booking API, so the seed builds it from the domain entity with explicit times and then applies the normal payment, check-in
and check-out steps.

## Support tickets

| Reservation | Ticket | Source | Status | Use it for |
|---|---|---|---|---|
| `SEED-OVERDUE-TICKET` | `VEHICLE_NOT_LEFT` | system | open, unassigned, high priority | claim it as `support`, then force check-out, evacuation, penalty |
| `SEED-ACTIVE` | `CANNOT_LEAVE` | driver (Jan) | open, unassigned, high priority | decide a held penalty, force check-out |
| `SEED-DONE-VNL` | `VEHICLE_NOT_LEFT` | owner (Marek) | open, unassigned | a report on an ended reservation, close or evacuate |
| `SEED-UPCOMING` | `OTHER` | driver (Jan) | open | a plain question for assignment and a reply |

Every ticket is created through the same service as the API, so none breaks the rules in
[support-workflows.md](support-workflows.md). Not seeded: warnings and blocks of accounts (do it from a ticket as `support`).

## What is not in the data set

Phone numbers and withdrawals (encrypted with the application key), spot photos (they need files), Premium plans and verification
requests (the SQL demo data has them). Reservations near "now" are not limited to the weekly availability: at night the
"approaching" or "active" windows can fall outside 06:00-22:00 because they were built from the clock, not through the booking check.

## How it is checked

`ScenarioSeederTest` (accounts and roles, listings, every state, the overdue auto-check-out with the penalty and the failed payment,
tickets, blocks, the refused overlapping block, idempotency, dates from the clock), `SeedGuardTest` and `ScenarioSeedConfigTest` (the
guard and the runner), and `ScenarioSeedStartupTest` (the whole application starts with the profile and seeds the database).
