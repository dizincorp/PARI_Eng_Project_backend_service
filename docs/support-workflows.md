# Support, penalty, evacuation and moderation workflows

Verified against the code on `main` and the tests listed at the end (PARI-93). Each section says **what happens today**. Where
the project has not decided a rule, the section says so and the item is also listed in [Open decisions](#open-decisions); nothing
in this file invents a business rule. Planned parts are marked *planned*.

Conventions: amounts in requests are PLN **minor units** (`amountMinor`, `feeMinor`: 1000 = 10.00 PLN), amounts in responses are PLN
decimals (`amount`, `penaltyTotal`). Errors have the shape `{ "code": "...", "message": "..." }`; `IllegalStateTransitionException`
is `409 INVALID_STATE`, validation errors are `400 VALIDATION_FAILED`, a missing reason is `400 BAD_REQUEST`.
Endpoint details (fields, types) are in [api-reference.md](api-reference.md), generated from the running application (springdoc), so
the contract follows the controllers.

## 1. Tickets and the reservation lifecycle

Endpoint: `POST /api/tickets` (`reservationId`, `type`, `description`, all required) answers `201` with a `TicketView`. Any driver or
owner can call it; the role checks below are done per ticket type in `SupportTicketService.create`.

| Rule | Behaviour today |
|---|---|
| Who may open a ticket | Only the **driver** or the **owner** of the reservation. Everybody else, including SUPPORT and ADMIN who are not a participant, gets `404 Reservation not found` (the reservation is not revealed). Support works on tickets, it does not open them. |
| `VEHICLE_NOT_LEFT` | **Owner only** (driver: `403`). From the reserved end (`409 TICKET_TOO_EARLY`, `details.availableAt`). The reservation must be `IN_PROGRESS` **or `COMPLETED`** (changed in PARI-93, see below); any other status is `409 SESSION_NOT_RUNNING`. One open ticket of this type per reservation (`409 TICKET_ALREADY_OPEN`). Priority `HIGH`. |
| `CANNOT_LEAVE` | **Driver only** (owner: `403`), only while the reservation is `IN_PROGRESS` (`409 SESSION_NOT_RUNNING` otherwise). No duplicate check. Priority `HIGH`. A ticket of this type open at check-out holds the overstay penalty (section 3). |
| `OTHER` | Driver or owner, in **any** reservation status, at any time. No duplicate check. |
| System ticket | `VehicleNotLeftJob` (every minute, `pari.support.auto-ticket-check-interval`) opens a `VEHICLE_NOT_LEFT` ticket with source `SYSTEM` and no reporter for a reservation that is still `IN_PROGRESS` `auto_ticket_after_end_minutes` (15) after its end. It does nothing when a ticket of that type exists for the reservation, **open or closed**, so a closed ticket is never reopened by the job. |
| Validation | A blank description is `400 BAD_REQUEST` "A description is required". |

Eligibility by reservation state (`VEHICLE_NOT_LEFT` is the owner's report; the check order is: participant, role, time, status, duplicate):

| Reservation state | `VEHICLE_NOT_LEFT` | `CANNOT_LEAVE` | `OTHER` |
|---|---|---|---|
| Upcoming, before its end (any status) | 409 `TICKET_TOO_EARLY` | 409 `SESSION_NOT_RUNNING` unless running | yes |
| `PENDING_PAYMENT`, `PENDING_APPROVAL`, `CONFIRMED` after the end | 409 `SESSION_NOT_RUNNING` | 409 `SESSION_NOT_RUNNING` | yes |
| `IN_PROGRESS` (running, also past the end) | yes | yes | yes |
| `COMPLETED` (manual check-out) | **yes** | 409 `SESSION_NOT_RUNNING` | yes |
| `COMPLETED` with the auto-checked-out marker | **yes** | 409 `SESSION_NOT_RUNNING` | yes |
| `CANCELLED`, `REJECTED`, `EXPIRED`, `NO_SHOW` | 409 `SESSION_NOT_RUNNING` | 409 `SESSION_NOT_RUNNING` | yes |

**The discrepancy that was reproduced.** The frontend lets the owner file `VEHICLE_NOT_LEFT` for an ended reservation. The service
required `IN_PROGRESS`, so once the session was completed (the driver checked out, or the automatic check-out ran at the end plus
`auto_checkout_after_end_minutes`, 60) the API answered `409 SESSION_NOT_RUNNING` "The parking session is not running". PARI-70 and
ADR 0005 give the owner the right to report from the end. **Fixed in PARI-93:** `COMPLETED` is now accepted. The ticket is then only
a support record: the spot is already released and the reservation is not reopened. Not changed: the other statuses stay refused.
Whether the owner may report an ended reservation at any later time or only within a window is open (see
[Open decisions](#open-decisions)); today there is no window.

Closed tickets: after support closes a `VEHICLE_NOT_LEFT` ticket the owner may open a new one (only an **open** one blocks a
duplicate); the system job never does.

Tickets are read with `GET /api/tickets` (the caller's tickets, newest first, `page`, `size` at most 50), `GET /api/tickets/{id}`
(participants and the reporter; internal notes hidden; `404` for anyone else) and `POST /api/tickets/{id}/messages` (public message;
`201`).

## 2. Evacuation and evacuation fees

Evacuation is **only recorded** here: there is no integration with a towing provider (planned). Support records a destination and
a status, and the driver is charged a fee. All commands need SUPPORT or ADMIN, a `reason`, and an **open** ticket (`409 TICKET_CLOSED`).

| Step | Endpoint | Rules | Side effects |
|---|---|---|---|
| Request | `POST /api/support/tickets/{id}/evacuation` (`destination`, `reason`) | Any open ticket, whatever its type or the reservation status. One evacuation per ticket (`409 INVALID_STATE`). The planned fee is the `evacuation_fee` platform setting (**placeholder 0.00 PLN**) | Status `REQUESTED`; notification `EVACUATION` to driver and owner; audit `SUPPORT_EVACUATION_REQUESTED` |
| Advance | `POST .../evacuation/status` (`status`, `reason`) | Only `REQUESTED` to `IN_PROGRESS` to `DONE`, in order (`409 INVALID_STATE`) | Notification to both; audit `SUPPORT_EVACUATION_IN_PROGRESS` / `_DONE`. On `DONE` the planned fee, when above zero, is charged once (operation key `evacuation-fee:<ticket>:evacuation-done`) |
| Change the planned fee | `POST .../evacuation/fee` (`feeMinor`, `reason`; the database accepts only 0 or more) | Needs an evacuation, not allowed after `DONE` (refund and add instead) | audit `SUPPORT_EVACUATION_FEE_ADJUSTED` |
| Add a fee | `POST .../evacuation/fees` (`amountMinor` > 0, `reason`, `operationId` 1 to 100 characters) | Needs an evacuation on this ticket (`409 INVALID_STATE`). Charged at once to the driver's default payment method | A `FEE` item; audit `SUPPORT_EVACUATION_FEE_ADDED`. The same `operationId` again returns the ticket unchanged: no second item, no second payment |
| Refund a fee | `POST .../evacuation/fees/{feeId}/refund` (`reason`) | The fee must belong to the ticket (`404`) and be `CHARGED` (`409 INVALID_STATE` for a failed one); once only (`409 EVACUATION_FEE_ALREADY_REFUNDED`); always in full | A `REFUND` item and a `REFUND` payment; audit `SUPPORT_EVACUATION_FEE_REFUNDED`. A refusal by the provider is `402 PAYMENT_FAILED` and rolls the command back |

What marking the evacuation `DONE` does **not** do: it does not check the vehicle out, does not release the spot, does not resolve or
close the ticket and does not change the reservation status. Support ends a running session with `force-check-out` and closes the
ticket with `close`; both are separate commands (section 5).

Fee payments, amounts and totals (this is why a command can succeed while the payment is `FAILED`):

- The command is accepted; the **charge** is then attempted on the driver's default payment method. When there is no payment method
  or the provider declines, the charge is stored as a `FAILED` payment and the fee item as `FAILED`. Nothing is thrown, because the
  support action itself (recording that the evacuation took place) is valid; the charge failing is data for support to follow up.
- `evacuationFeeTotal` = charged fees minus refunds. `FAILED` fees are listed but **not counted**. Pending (planned) fees are not in
  the total: `TicketView.evacuationFee` is only the fee planned for that ticket.
- A failed fee is final and there is **no retry endpoint**. To try again, add a new fee with a **new** `operationId` (the old one
  returns the unchanged ticket). The automatic charge on `DONE` happens once per ticket: when it failed, add a fee by hand.
- Currency is PLN only. Fees never change `finalCost`, the parking price or the owner's payout.
- Evacuation fees are not shown in the driver's own reservation views yet (*planned*).

## 3. Penalties: two separate flows

### 3.1 Overstay penalty (automatic)

| | |
|---|---|
| Created by | The system at check-out: manual (`POST /api/reservations/{id}/check-out`), automatic (`auto_checkout_after_end_minutes`, 60 after the end) or support `force-check-out`. |
| Target | The driver of the reservation; the owner is notified. |
| Calculation | If the driver is still checked in after the reserved end by **more than** `overstay_grace_minutes` (15), the whole overstay counted from the reserved end (not from the end of the grace) is charged: hourly rate x `overstay_penalty_multiplier` (1.50, placeholder) x started minutes / 60. Up to the grace: 0. Amounts are in `Money` minor units. |
| Payment | At check-out, on the driver's default payment method, as a `PENALTY` payment; `FAILED` when there is no method or it is declined (the check-out is not blocked). |
| Hold | When a `CANNOT_LEAVE` ticket is open at check-out the penalty is **held** (`penaltyPending = true`) and nothing is charged. Support decides with `settle-penalty` (`waive` true or false). While it is held the ticket cannot be closed (`409 PENALTY_PENDING`). |
| Waive | `settle-penalty` with `waive: true` zeroes the overstay penalty and sets `finalCost` back to the parking price. It applies to this flow only. |
| Duplicates | One overstay penalty per reservation (it is set once by the check-out). |
| Refund (PARI-100) | A **charged** overstay penalty is returned in full with `POST /api/support/tickets/{id}/commands/refund-overstay-penalty` (see 3.3). |

### 3.2 Penalty assessed by support (PARI-87)

| | |
|---|---|
| Created by | A support employee: `POST /api/support/tickets/{id}/penalties` (`amountMinor` > 0, `reason`, `operationId` 1 to 100, optional `targetUserId`). |
| Target | **The driver only** (the only participant with a payment method). Any other `targetUserId` is `400 PENALTY_TARGET_NOT_PAYER`. |
| Reservation state | `IN_PROGRESS`, `COMPLETED` or `NO_SHOW`; otherwise `409 PENALTY_RESERVATION_NOT_ELIGIBLE`. It stays possible after completion. |
| Duplicates | The same `operationId` on the same ticket returns the ticket unchanged. A different `operationId` creates another penalty: there is no limit on how many and **no upper limit on the amount**. |
| Lifecycle | `PENDING` -> `CHARGED` (payment succeeded), `FAILED` (payment refused or no payment method) or `WAIVED`. Only `PENDING` can change (`409 INVALID_STATE`). Assessing collects nothing. |
| Charge / waive | `POST .../penalties/{penaltyId}/charge` and `.../waive` (`reason`). `FAILED` is final: assess a new penalty to try again. There is **no refund** of a `CHARGED` assessed penalty (*not implemented*); `refund-overstay-penalty` does not touch it. |
| Totals | `penaltyTotal` = sum of the `CHARGED` ones. Pending, waived and failed ones do not count. A penalty never changes `finalCost` or the owner's payout. |
| Closing | The ticket cannot be closed while one of its penalties is `PENDING` (`409 PENALTY_PENDING`). |
| Audit | `SUPPORT_ASSESS_PENALTY`, `SUPPORT_CHARGE_PENALTY_ASSESSED`, `SUPPORT_WAIVE_PENALTY_ASSESSED` with `penaltyId`, `amountMinor`, `targetUserId`, `penaltyState`, `paymentStatus`. |
| Notifications | None for penalties (*planned*). |

**"Waive penalty" means two different commands.** `settle-penalty` with `waive: true` waives the **held overstay** penalty;
`penalties/{id}/waive` waives an **assessed** one. Neither touches the other.

### 3.3 Refund of a charged overstay penalty (PARI-100)

| | |
|---|---|
| Endpoint | `POST /api/support/tickets/{id}/commands/refund-overstay-penalty`, body `reason` (not blank) and `operationId` (1 to 100 characters). SUPPORT or ADMIN; the ticket must be open and belong to the reservation. Returns the updated `TicketView`. |
| What is refunded | The successful automatic `PENALTY` payment of the reservation, **in full** (the payment that does not belong to a penalty support assessed). The ledger gets a new `REFUND` payment; nothing is overwritten. |
| States | `overstayPenalty.state` is `NONE`, `PENDING` (held), `CHARGED`, `FAILED`, `WAIVED` or `REFUNDED`. Only `CHARGED` can be refunded. Otherwise `409 OVERSTAY_PENALTY_NOT_CHARGED` with `details.state`; after a refund `409 OVERSTAY_PENALTY_ALREADY_REFUNDED`. A held penalty is decided with `settle-penalty`. |
| Totals | `overstayPenalty` and `finalCost` keep the billed amount; the owner's payout is not changed. The refund is shown in `overstayPenalty` (`amount`, `state`, `refundedAmount`, `refundedAt`, `netAmount`), in `TicketView` and in `SupportReservationView`. |
| Idempotency | The same `operationId` on the same ticket returns the ticket unchanged. The reservation row is locked during the command and the refund payment is stored with a unique key, so requests at the same moment refund once (tested with parallel transactions). |
| Payment refused | `402 PAYMENT_FAILED`; the command is rolled back (no refund, no marker, no audit entry). |
| Audit | `SUPPORT_REFUND_OVERSTAY_PENALTY` with the reason, `amountMinor`, `operationId`, `refundPaymentId`, `targetUserId`. |
| Notification | The driver gets an in-app `OVERSTAY_PENALTY_REFUNDED` notification (amount, reservation, ticket). The owner is not notified. |
| Not covered | Partial refunds, refunding a `FAILED` penalty (nothing was collected) and an assessed penalty. |

## 4. Warnings, blocking and unblocking

| Question | Answer today |
|---|---|
| Who issues a sanction from a ticket | SUPPORT or ADMIN: `POST /api/support/tickets/{id}/commands/sanction` (`userId`, `type` `WARNING`, `BLOCK` or `UNBLOCK`, `reason`). The target must be the driver, the owner or the reporter of the ticket's reservation (`400 SANCTION_TARGET_NOT_PARTICIPANT`). |
| Warning count | **Per account**, across tickets. It counts `WARNING` entries since the account's last `UNBLOCK`. Stored in the `sanctions` history, which is never deleted. |
| Threshold | Platform setting `warning_limit` (**3, placeholder** in the migration). |
| Automatic block | When a warning makes the count reach the limit and the account is not blocked, the same transaction blocks the account and writes a `BLOCK` entry with the reason "Warning limit reached". The response audit data has `autoBlocked: true`. |
| Failure of the block | The warning, the block and the audit entry are one transaction: when anything fails, none of them is saved (the failed command leaves no audit entry). |
| Concurrency | No lock is taken on the user row. Two warnings issued at the same moment are counted from what each transaction sees, so a burst could in principle miss the limit once or write a second `BLOCK` entry; this is **not** covered by a test (open decision). |
| Unblock | `UNBLOCK` through the ticket command or `POST /api/support/users/{userId}/unblock` (`reason` required, no ticket needed; `409 NOT_BLOCKED` when the account is not blocked; `409 ALREADY_BLOCKED` for blocking a blocked account). The history stays and **the warning count starts again from zero**. |
| Who may unblock | Any SUPPORT or ADMIN; there is no ADMIN-only restriction. |
| Reading | `GET /api/support/users/{userId}/moderation` (blocked flag, `warningCount`, `warningLimit`, history newest first) and `GET /api/support/users/blocked`. |
| Effect on the blocked user | The account cannot log in (`403 The account is blocked`), cannot book (`ReservationService.book`), cannot create a listing (`SpotService.create`), and gets no payout (`OwnerPayoutService`). An access token that was already issued stays valid until it expires (`ttl` 24 h): reading, tickets and messages still work during that time. There is no token revocation or refresh-token store yet (*planned*). |
| Notifications | None for sanctions (*planned*). |
| Audit | `SUPPORT_SANCTION_WARNING` / `_BLOCK` / `_UNBLOCK` on the ticket with `targetUserId`, `sanctionType`, `targetBlocked`, `autoBlocked`; `SUPPORT_UNBLOCK` on the user for the ticketless unblock. |

## 5. Support and admin action matrix

All commands: `POST /api/support/tickets/{id}/commands/<name>` with `{ "reason": "..." }` unless the body is listed. SUPPORT and ADMIN
have **the same rights**: every endpoint under `/api/support` checks `SUPPORT` or `ADMIN` in the service (`403 The SUPPORT role is
required`, `401` without a token). The ticket must be open (`409 TICKET_CLOSED`), the reason must not be blank, and every success
writes one audit entry `SUPPORT_<COMMAND>` (a failed command writes nothing).

| UI action | Endpoint | Prerequisite | State change | Failure |
|---|---|---|---|---|
| Take the ticket | `POST /tickets/{id}/claim` | Open; not claimed by someone else | Assigned, `IN_PROGRESS` | `409 TICKET_ALREADY_CLAIMED` |
| Assign / reassign | `commands/reassign` (`assigneeId`, `reason`) | Assignee is SUPPORT or ADMIN | Assigned, `IN_PROGRESS`; audit has `previousAssigneeId` | `403`/`404` for a bad assignee |
| Release | `commands/release` | The ticket is assigned | Unassigned, `OPEN`, messages and resolution kept | `409 INVALID_STATE` when unassigned |
| Force check-out | `commands/force-check-out` | Reservation `IN_PROGRESS` | `COMPLETED`; an overstay penalty is charged (or held, see 3.1); spot released | `409 INVALID_STATE` when not running |
| Settle held penalty | `commands/settle-penalty` (`waive`, `reason`) | A held overstay penalty | Waived, or charged now | `409 INVALID_STATE` when none is held |
| Refund charged overstay penalty | `commands/refund-overstay-penalty` (`reason`, `operationId`) | Overstay penalty `CHARGED` (section 3.3) | Full `REFUND` payment; `overstayPenalty.state` `REFUNDED`; driver notified | `409 OVERSTAY_PENALTY_NOT_CHARGED`, `409 OVERSTAY_PENALTY_ALREADY_REFUNDED`, `402 PAYMENT_FAILED` |
| Cancel reservation and refund | `commands/cancel-with-refund` | `PENDING_PAYMENT`, `PENDING_APPROVAL` or `CONFIRMED` | `CANCELLED`, full refund, driver and owner notified | `409 INVALID_STATE`; `402` when the refund is refused |
| Correct a no-show | `commands/correct-no-show` | `NO_SHOW` | `CANCELLED`, full refund | `409 INVALID_STATE` |
| Warn / block / unblock | `commands/sanction` (`userId`, `type`, `reason`) | See section 4 | See section 4 | See section 4 |
| Close the ticket | `commands/close` | No held overstay penalty, no `PENDING` assessed penalty | `RESOLVED` then `CLOSED`; the reason is the resolution shown to participants | `409 PENALTY_PENDING` |
| Start, update, finish evacuation | `evacuation`, `evacuation/status` | See section 2 | See section 2 | See section 2 |
| Add, refund evacuation fee | `evacuation/fees`, `.../{feeId}/refund` | See section 2 | See section 2 | See section 2 |
| Assess, charge, waive a penalty | `penalties`, `.../charge`, `.../waive` | See section 3.2 | See section 3.2 | See section 3.2 |
| Reply or leave a note | `POST /tickets/{id}/messages` (`body`, `internalNote`) | Open or closed ticket | A public reply notifies the reporter once (`TICKET_REPLY`, idempotent); an internal note notifies nobody | `400` for an empty body |
| Read | `GET /tickets`, `/tickets/staff`, `/tickets/{id}/reservation`, `/tickets/{id}/audit`, `/users/...` | | none | |

Use cases for the support-actions screen: *the vehicle is still there after the end* (owner ticket or the system one): claim, add an
internal note, `force-check-out` if the session still runs, optionally `evacuation` (request, in progress, done) and a fee, assess or
settle a penalty if the driver should pay, then `close`. *The driver cannot leave*: the overstay penalty is held; `settle-penalty`
decides it, then `close`. *The wrong no-show*: `correct-no-show`. *Abuse*: `sanction` (`WARNING`, `BLOCK`); the unblock needs no
ticket. ADMIN has no extra action today (the admin-only part of the screen is *planned*).

## 6. Endpoint and DTO inventory

The generated list of every endpoint, field and type is [api-reference.md](api-reference.md) (section *Support tickets and
moderation*). The contract is produced by springdoc from the controllers and DTO records, so it cannot differ from the code; the
behaviour above is what the fields do. Request records (`SupportController`, `TicketController`): `CreateTicketRequest`,
`MessageRequest`, `NoteRequest`, `ReasonRequest`, `SettlePenaltyRequest`, `SanctionRequest`, `ReassignRequest`, `EvacuationRequest`,
`EvacuationStatusRequest`, `EvacuationFeeRequest`, `AssessPenaltyRequest`, `EvacuationFeeItemRequest`, `RefundOverstayPenaltyRequest`. Responses: `TicketView` (with
`penalties`, `penaltyTotal`, `evacuationFees`, `evacuationFeeTotal`, `overstayPenalty`), `SupportReservationView`, `AuditEventView.Page`, `StaffView`,
`ModerationService.ModerationView` and `BlockedUserView`.

Idempotency in one place: assessing a penalty, adding an evacuation fee and refunding the overstay penalty are idempotent per `operationId` (per ticket); the charge of
a penalty is derived from the penalty id, so it happens once; the automatic fee on `DONE` has a fixed key; the system ticket is created
once per reservation; a support reply notification is created once per reply. Every other command is not idempotent: repeating it
either changes the state again or is refused with `409`.

## Open decisions

These are **not decided** by the project; the code does what is written above, and nothing was changed for them.

1. Is there a time window for the owner's `VEHICLE_NOT_LEFT` report on a `COMPLETED` reservation? (none today)
2. Should a block take effect for tokens that were already issued (revocation, or a check in the security filter)?
3. Should warning and block be atomic under concurrent requests (lock the user row)? Should an unblock be limited to ADMIN?
4. Should an owner be a possible target of a penalty? Today only the driver (the payer) can be.
5. Is there a maximum penalty amount and a maximum evacuation fee? Values for `evacuation_fee` (0.00), `warning_limit` (3) and
   `overstay_penalty_multiplier` (1.50) are placeholders from the migrations.
6. Should a `CHARGED` **assessed** penalty be refundable (the automatic overstay penalty is, since PARI-100), should a `FAILED` fee or penalty be retryable, and should drivers be notified of
   penalties, fees and sanctions?
7. Should `DONE` evacuation also end the session, release the spot or close the ticket?
8. Should "waive penalty" be a single action in the UI for both the overstay and the assessed penalty?

## What was verified

Tests (Docker needed): `TicketEligibilityTest` (state matrix of section 1 and the `COMPLETED` fix), `SupportTicketServiceTest`
(tickets, queue, claim, release, force check-out, evacuation flow, held penalty, audit trail, sanctions, warning limit, unblock,
support context), `SupportChargesTest` (penalty and evacuation-fee outcomes: charged, failed, waived, refunded, idempotency, totals),
`SupportControllerTest` (roles and status codes), `TicketTest` (domain rules). Not covered by a test: concurrent warnings,
the behaviour of an already issued token after a block, notifications for penalties and sanctions (none exist).
