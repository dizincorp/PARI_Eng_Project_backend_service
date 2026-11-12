# 0005. Reservation buffer, auto check-out and support tickets

Status: accepted

## Context

Check-in and check-out today have gaps: a `CONFIRMED` reservation that is never used stays `CONFIRMED` forever (no no-show); auto check-out runs at `end + 15 min`, sharing the penalty grace setting; the spot is blocked only for `[starts_at, ends_at)`, so a driver who cannot leave collides with the next booking; the frontend allows check-in 5 minutes early but the backend does not; and only a data layer exists for problem reports (`Report`), with no service, no support UI and no way to deal with a car that does not leave.

## Decision

1. **Check-in** is by the driver, from `start - 5 min` to `E`, enforced in the backend too.
2. **No-show**: a never-checked-in `CONFIRMED` reservation becomes new status `NO_SHOW` at `E`. No refund, normal owner payout, spot freed.
3. **Buffer**: an unpaid hour after `E`. Stored as `blocked_until = E + reservation_buffer_minutes` and used by the exclusion constraint, `SLOT_HOLDING` and the native search query, which must stay in sync. Leaving the holding statuses frees the buffer.
4. **Overstay penalty** and its 15-minute grace stay unchanged.
5. **Auto check-out** moves to `E + 60 min` (`auto_checkout_after_end_minutes`), separate from the penalty grace. Final status stays `COMPLETED`, with an `auto_checked_out` marker. The spot stays blocked while a `VEHICLE_NOT_LEFT` ticket is open.
6. **Tickets** are a separate support module (may reuse `Report` as base). Sources: owner (from `E`), driver, system (auto at `E + 15 min` if still `IN_PROGRESS` and no open ticket). One open `VEHICLE_NOT_LEFT` ticket per reservation. Shared queue, self-claim, never auto-closed.
7. **Support** acts only through explicit audited commands with a mandatory reason, no raw data editing. Roles `SUPPORT` and `ADMIN`.
8. **Evacuation** is recorded (destination free text, status); the driver pays `evacuation_fee`. No tow-company integration.
9. A driver's open `CANNOT_LEAVE` ticket holds the penalty as pending until support decides.
10. All durations and fees are `platform_settings`. Legacy past-end `CONFIRMED` rows move to `NO_SHOW` without any charge; new rules apply to new reservations only.

## Consequences

- One more status and a `blocked_until` column; a migration must update the exclusion constraint, `SLOT_HOLDING` and the search query together.
- Spots can stay blocked beyond the paid period, reducing bookable time slightly.
- Support gains real power, so the audit log is part of the design.
- Deferred: extending a reservation, splitting penalties and evacuation fees with the owner (payout release is not implemented), tow-company integration, dashboards.

## Alternatives rejected

- Charging for the buffer: punishes drivers for being blocked.
- Free grace for the whole buffer: makes overstaying free.
- A new final status for auto check-out: grows the state machine; a marker is enough.
- Auto-closing tickets on check-out: loses the decision owner and reason.

## Implementation notes

- Migration `V10`: status `NO_SHOW`; reservation columns `blocked_until`, `auto_checked_out`, `penalty_pending`, `reminders_sent`; `reservations_no_overlap` now uses `[starts_at, blocked_until)`; the support columns on `reports` (type, source, priority, evacuation); a partial unique index for one open `VEHICLE_NOT_LEFT` ticket per reservation; new `platform_settings`.
- Tickets reuse the `reports` table and the `Report` entity. A `SYSTEM` ticket has no reporter.
- The buffer is symmetric: a new reservation also needs a free hour after its own end, because its `blocked_until` takes part in the constraint. Two bookings of one spot therefore need at least one hour between them.
- While a `VEHICLE_NOT_LEFT` ticket is open, auto check-out does nothing and the scheduler keeps pushing `blocked_until` one buffer ahead. If a later booking already starts there, the extension fails and is logged; support still sees the ticket.
- `evacuation_fee` is a placeholder (0.00) until the team decides the amount.
- Correcting a no-show cancels the reservation and refunds the driver in full (the design did not define a different outcome).
