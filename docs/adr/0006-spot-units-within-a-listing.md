# 0006. Independently bookable spaces (units) within one listing

Status: accepted

## Context

PARI-65: one listing can be a physical area with several numbered spaces at the same address. Today a reservation holds `spot_id`, the exclusion constraint `reservations_no_overlap` is keyed on it, and availability, blocks, search, occupancy and the calendar are scoped to the spot. A listing can therefore be booked by one driver at a time.

## Decision

1. **A new table `spot_units`.** A listing (`parking_spots`) keeps the shared address, photos, description, prices, gate PIN and access instructions. Each unit has a stable id and a label (`#1`, `#2`, ...). The id is the identifier; the label is only for display and is unique within the listing among live units. Units are soft-deleted (`removed_at`), so old reservations keep their unit and label.
2. **Only the label is a per-unit override.** Everything else is inherited from the listing. Price, dimensions and PIN overrides are not part of this decision, because they touch quotes, payouts and commission.
3. **Existing listings** get one default unit `#1` in the migration, and their reservations are backfilled. A new listing always starts with unit `#1`. `unitId` is optional when booking.
4. **A reservation holds a unit.** `reservations.unit_id` is required and `reservations.unit_label` is a snapshot, so a later rename does not rewrite old receipts. The exclusion constraint `reservations_no_overlap` is keyed on `unit_id` and keeps the buffer (`blocked_until`) and the status list.
5. **Choosing a unit** (delivered with the booking PR): the driver may pass `unitId`; when omitted, the first free unit by creation order is assigned. Assignment locks the parent listing row, so it is serialized per listing. The constraint stays the final guard.
6. **Search** treats a listing as available when at least one unit is free and returns `freeUnits`.
7. **Blocks.** Weekly availability rules stay at listing level. Blocked periods and exceptions take an optional `unit_id`; no unit means a closure for every unit.
8. **Capacity.** The owner adds units and deletes specific units. A unit with an active or future reservation cannot be deleted, and the last live unit cannot be deleted (archive the listing instead). The number of units is limited by the platform setting `max_units_per_spot`, **a placeholder of 50** (the team has not decided the limit).
9. **Occupancy** moves to the unit; the listing is occupied only when every unit is.
10. **Unchanged:** reviews and ratings (per listing), verification and inspection requests (per listing), the PIN and access instruction snapshots.

Delivery is in three pull requests: (1) schema, units, owner endpoints and the constraint; (2) booking with `unitId`, auto-assignment and search; (3) blocks, calendar, occupancy and the label in all views, notifications and receipts. Between the first and the second, a booking is always placed on the first live unit, so a listing with more units can be set up but is not yet bookable in parallel.

## Alternatives rejected

| Option | Why not |
| --- | --- |
| A capacity counter on the listing | No stable id, no label, no place for per-unit blocks. |
| Separate listings grouped together | Duplicates photos and description and breaks "one parent listing". |
| Application-level capacity check without a constraint | Cannot guarantee no double booking under concurrent requests. |
| Per-unit prices, dimensions and PIN now | Not asked for and affects pricing and payouts; can be added later on the same table. |

## Consequences

- One migration rebuilds the exclusion constraint. `SLOT_HOLDING`, the constraint and the native search query must still be changed together; the search query changes in the booking PR.
- Every reservation row now has a unit; raw SQL inserts without one get the listing's first unit through a trigger.
- A reduced unit count cannot be done by setting a number: the owner deletes units one by one.
- `max_units_per_spot` is a placeholder and must be confirmed.
