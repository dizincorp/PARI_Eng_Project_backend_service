# ADR 0004: Payment behind a gateway port, mock implementation first

- **Status:** proposed
- **Date:** 2026-10-18
- **Deciders:** project team (to confirm)

## Context

Booking (F13) needs a payment step, and the plan names Stripe as the provider (NF03). Stripe keys, webhooks and a test account do not exist yet, and development must not move real money.

## Decision

Everything that talks to a payment provider goes through the `PaymentGateway` interface (`charge`, `refund`). The only implementation now is `MockPaymentGateway`, active unless `pari.payment.provider` says otherwise. It never calls a network and follows Stripe's test tokens: `pm_card_chargeDeclined` and `pm_card_chargeDeclinedInsufficientFunds` are declined, any other token succeeds. A Stripe adapter will be a second implementation of the same interface.

`PaymentService` owns the money flow: the `payments` rows (charge, refund), the owner's `payouts` in soft escrow (F17) and the refund-and-cancel-payout step.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Call Stripe test mode directly | Needs keys and a Stripe account, ties tests to the network, and leaves no clean seam for a second provider. |
| Skip payments until Stripe is ready | Booking could not be finished or tested end to end. |

## Consequences

- Payments are fake until the Stripe adapter exists. Nothing in the reservation flow changes when it is added, except webhook handling for asynchronous confirmation (the mock confirms synchronously inside the booking transaction).
- The charge happens inside the booking transaction. With a real provider this must be split (reserve, charge outside the transaction, confirm by webhook) so a database transaction is not held open during a network call.
- Refund policy is a PLACEHOLDER: always a full refund before check-in, because the plan defines no cancellation fees.
- Overstay penalties (F19) and payout release (F17 job) are not implemented yet.
