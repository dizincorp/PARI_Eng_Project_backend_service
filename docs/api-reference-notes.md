These notes are written by hand and appended to the generated tables above. They describe what the OpenAPI document cannot: who may call what, how errors look, and the conventions every endpoint follows. They describe the code on `main`; planned behaviour is marked *planned*.

### Authentication

- `POST /api/auth/login` returns `{ token, tokenType, expiresIn, user }` (`expiresIn` in seconds; a blocked account gets `403`). The token is a JWT signed with HS256 (`PARI_JWT_SECRET`), valid 24 h (`pari.security.jwt.ttl`). Its claims are `sub` (the user id) and `roles` (for example `["OWNER"]`). Send it as `Authorization: Bearer <token>`.
- There are no refresh tokens and a token cannot be revoked: after a password change (`POST /api/auth/change-password`) or logout, tokens issued earlier stay valid until they expire. *Planned*: refresh tokens (the `auth_tokens` table exists).
- Public endpoints (no token): register, login, `GET /api/spots` (search), `GET /api/spots/{id}`, `GET /api/spots/{id}/reviews`, `GET /api/spots/{id}/availability-rules`, `GET /api/spots/events` (SSE), `POST /api/reservations/quote`, `GET /media/**`, Swagger UI and `/v3/api-docs`. `GET /api/spots/mine` needs a token.
- `X-User-Id: <uuid>` is accepted instead of a token **only** when `pari.security.jwt.dev-header-auth=true` (profile `local` and the tests). It is off by default and must never be on in production. A frontend that sends both headers is fine: the header is ignored when a token is present.
- A missing or invalid token gives `401 UNAUTHENTICATED`.

### Who may do what

The user id always comes from the token, never from the request body. Rules are enforced in the services.

| Action | Rule | Failure |
| --- | --- | --- |
| Register | every account gets `DRIVER` and `OWNER`; the optional `role` (`DRIVER` or `OWNER`, else `400`) only sets the starting panel; e-mail and phone number unique | `400`, `409 EMAIL_ALREADY_REGISTERED`, `409 PHONE_ALREADY_REGISTERED` |
| Create a space (`POST /api/spots`) | `OWNER` role, account not blocked, `ownershipDeclared = true` (F09), price > 0 | `403`, `400` |
| Edit, archive, photos, calendar of a space | the owner of the space, or an `ADMIN` | `404` unknown space, `403` someone else's |
| See a space | public while `ACTIVE`; any other status only for its owner | `404` |
| Book (`POST /api/reservations`) | `DRIVER` role, not the owner of the space, space bookable, owner offers the time, slot free, vehicle belongs to the driver and has no other reservation (any space, statuses `PENDING_PAYMENT`, `PENDING_APPROVAL`, `CONFIRMED`, `IN_PROGRESS`; intervals are half-open, so back-to-back is fine) | `403`, `409 SPOT_NOT_BOOKABLE`, `409 SPOT_NOT_AVAILABLE`, `409 SPOT_ALREADY_RESERVED`, `409 VEHICLE_TIME_CONFLICT` (`details`: `conflictingReservationId`, `conflictingStart`, `conflictingEnd`), `402 PAYMENT_FAILED` |
| Accept or reject a request | the owner of the space; status must be `PENDING_APPROVAL` | `403`, `409 INVALID_STATE` |
| Cancel | the driver or the owner of the reservation; only before check-in | `409 INVALID_STATE` |
| Check in | the driver of the reservation, with the PIN or the QR token, from 5 minutes before the start until the reserved end; the booked interval and amount do not change | `403 Invalid access code`, `409 CHECK_IN_TOO_EARLY` (body has `details.availableAt`, the moment check-in opens), `409 INVALID_STATE` |
| Check out, active session | the driver of the reservation | `409 INVALID_STATE`, `404` |
| Review a space | the driver of a `COMPLETED` reservation, once | `409 RESERVATION_NOT_COMPLETED`, `409 ALREADY_REVIEWED` |
| Owner analytics, payout balance, withdraw, history | `OWNER` role | `403` |
| Withdraw | amount (at most two decimals) not above the available balance | `409 INSUFFICIENT_BALANCE`, `402 PAYMENT_FAILED` |
| Premium (`POST /api/subscriptions/premium`) | `OWNER` role, no active plan | `409 ALREADY_PREMIUM`, `402 PAYMENT_FAILED` |
| Vehicles | own vehicles only; unique plate per user; not deletable while used | `409 VEHICLE_ALREADY_EXISTS`, `VEHICLE_IN_ACTIVE_RESERVATION`, `VEHICLE_HAS_RESERVATION_HISTORY` |
| Block a period in the calendar | the owner; refused while a reservation holds a slot in it | `409 PERIOD_HAS_RESERVATIONS` |
| Archive a space | the owner; refused while a reservation holds a slot | `409 SPOT_HAS_ACTIVE_RESERVATIONS` |

### Errors

Every error has the same body, also for the security filters:

```json
{ "errorCode": "SPOT_ALREADY_RESERVED", "message": "The selected time is already reserved", "statusCode": 409, "timestamp": "2026-11-03T10:15:30Z" }
```

Some business errors add a `details` object with machine-readable facts (it is absent otherwise), for example `{ "errorCode": "CHECK_IN_TOO_EARLY", ..., "details": { "availableAt": "2026-11-06T12:55:00Z" } }`.

| Status | `errorCode` | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | body validation failed; `message` lists `field reason` pairs |
| 400 | `BAD_REQUEST` | unreadable body, wrong parameter type, a rule such as "radius must be between 1 and 50000 meters" |
| 400 | `INVALID_CURRENT_PASSWORD` | wrong current password in change-password |
| 401 | `UNAUTHENTICATED` | no or invalid token |
| 401 | `INVALID_CREDENTIALS` | wrong e-mail or password at login (the same answer for an unknown e-mail) |
| 402 | `PAYMENT_FAILED` | the payment gateway refused (card declined, insufficient funds, refund failure) |
| 403 | `FORBIDDEN` | role or ownership rule |
| 404 | `NOT_FOUND` | unknown id, or a resource the caller may not see |
| 409 | `INVALID_STATE` | the action is not possible in the current status of the object |
| 409 | business codes | `SPOT_ALREADY_RESERVED`, `SPOT_NOT_AVAILABLE`, `SPOT_NOT_BOOKABLE`, `INSUFFICIENT_BALANCE`, `ALREADY_REVIEWED`, ... (see the table above) |
| 409 | `CONSTRAINT_VIOLATION` | safety net: a database constraint rejected the write (for example two concurrent bookings of one slot) |
| 413 | `FILE_TOO_LARGE` | upload above 5 MB |

A booking whose payment is declined answers `402`, and the reservation is stored as `CANCELLED` with the reason, so the slot is free again.

### Conventions

- **Time**: ISO-8601 instants in UTC (`2026-11-03T10:00:00Z`). Weekly availability and the owner analytics use the business time zone `Europe/Warsaw` (`pari.time-zone`).
- **Money**: the API uses decimal amounts in PLN with two decimals (`12.50`); the database stores whole grosze (`hourly_price_minor = 1250`). The conversion is done only at the edge (`Money`).
- **Prices are calculated on the server**: the client sends the space, the time and the payment-method token, never an amount. `POST /api/reservations/quote` previews the price (also public) and booking recalculates it.
- **Paging**: `page` (from 0) and `size` (default 20, at most 50) on lists; list endpoints answer with a plain JSON array.
- **Map search** (`GET /api/spots`): `lat`, `lng` are required, `radiusMeters` defaults to 5000 and is at most 50000, optional `maxPrice`, `onlyVerified`, `start` + `end` (both or none). Premium owners come first, then by distance. Without `lat` and `lng` the answer is `400`.
- **Rental types**: `bookingType` is `HOURLY` (start and end), `DAILY` (whole days) or `MONTHLY` (start and `durationMonths`, 1 to 36, with a discount from 3 months on).
- **Uploads**: `multipart/form-data`, field `file` (and `category` for `POST /api/media/upload`), JPEG, PNG or WebP (the type is detected from the bytes, not from the name), at most 5 MB. The answer holds the public URL `<PARI_PUBLIC_BASE_URL>/media/<key>`.
- **Live occupancy** (`GET /api/spots/events`): Server-Sent Events. The event name is `occupancy` and the data is `{ "spotId": "...", "occupancy": "FREE" | "OCCUPIED", "at": "..." }`. A client must listen with `addEventListener("occupancy", ...)`; `onmessage` does not receive named events.
- **Aliases**: `GET /api/owner/payouts/history` is also served as `/withdrawals`; blocked periods as `/api/spots/{id}/blocked-periods` and `/api/spots/{id}/calendar/blocks`.

### Not implemented yet

Refresh tokens and logout on the server, e-mail and phone verification, password reset, support reports and sanctions, the inspection workflow for verification requests, extending a reservation (`/extend`), the PDF confirmation, real Stripe payments, rate limiting of login. The tables for some of these already exist (see [data-dictionary.md](data-dictionary.md)).
