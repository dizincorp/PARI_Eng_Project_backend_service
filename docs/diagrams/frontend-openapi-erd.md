# ERD derived from the frontend OpenAPI contract

Source: `dizincorp/PARI_Eng_Project_front` -> `docs/api/openapi.yaml` (components/schemas).
Field names are the frontend's (Polish) names, mapped to English entity names.

## Diagram

```mermaid
erDiagram
    USER ||--o{ VEHICLE : owns
    USER ||--o{ PARKING_SPOT : "lists (role Wlasciciel)"
    USER ||--o{ RESERVATION : "books (role Kierowca)"
    USER ||--o{ NOTIFICATION : receives
    VEHICLE ||--o{ RESERVATION : "used in (nrRejestracyjny)"
    PARKING_SPOT ||--o{ SPOT_PHOTO : has
    PARKING_SPOT ||--o{ BLOCKED_DATE : "blocked on"
    PARKING_SPOT ||--o{ RESERVATION : "reserved as"
    RESERVATION ||--o| PARKING_SESSION : "check-in creates"
    RESERVATION ||--o| PAYMENT : "paid by"

    USER {
        string id PK "usr-101"
        string email UK
        string passwordHash "haslo (write-only)"
        string imie
        string nazwisko
        string telefon
        enum rola "Kierowca | Wlasciciel (active role)"
        enum statusKonta "AKTYWNE | OSTRZEZONE | ZABLOKOWANE"
        string avatarUrl
        int punktyLojalnosciowe
        bool premiumActive "owner premium F08"
        datetime premiumExpiresAt
    }
    VEHICLE {
        string id PK
        string userId FK
        string model
        string nrRejestracyjny
        bool domyslny
    }
    PARKING_SPOT {
        string id PK "spot-42"
        string ownerId FK
        string nazwa
        string opis
        string adres
        string miasto
        string kodPocztowy
        double lat
        double lng
        decimal cenaZaGodzine
        decimal cenaZaDzien
        decimal szerokosc "m"
        decimal dlugosc "m"
        enum typ "Podziemny | Naziemne zadaszone | Podjazd prywatny | Garaz indywidualny"
        string pietro
        enum status "AVAILABLE | OCCUPIED | BLOCKED | PENDING_VERIFICATION"
        bool statusRealTime "F04"
        bool czyZweryfikowane "F10"
        bool autoAccept
        string kodPin
        string instrukcjaWjazdu
        bool oswiadczeniePrawne "F09"
        string[] udogodnienia "Zadaszone, Monitoring, Ladowarka EV, Oswietlenie, Podziemny"
        decimal ocena "derived"
        int liczbaOpinii "derived"
    }
    SPOT_PHOTO {
        string id PK
        string spotId FK
        string url
        int position
    }
    BLOCKED_DATE {
        string id PK
        string spotId FK
        date data
        string powod
    }
    RESERVATION {
        string id PK "RSV-2024-08-14-892"
        string spotId FK
        string driverId FK
        string vehicleId FK
        string vehiclePlate "snapshot"
        datetime czasOd
        datetime czasDo
        decimal stawkaGodzinowa "snapshot"
        decimal oplataSerwisowa
        decimal totalCost
        enum status "POTWIERDZONA | OPLACONA | W_TRAKCIE | ZAKONCZONA | ANULOWANA"
        string pinCode
        string qrCodeData
        enum softEscrowStatus "BLOCKED_UNTIL_END_PLUS_24H | RELEASED_TO_OWNER | REFUNDED"
        decimal zwrotKwoty
        string cancelReason
    }
    PARKING_SESSION {
        string id PK "ses-99"
        string reservationId FK,UK
        datetime checkInTime
        double checkInLat
        double checkInLng
        datetime checkOutTime
        decimal ostatecznyKoszt
        decimal karaUmowna
    }
    PAYMENT {
        string id PK
        string reservationId FK
        enum metodaPlatnosci "STRIPE | BLIK"
        string stripePaymentMethodId
        decimal amount
        string waluta "PLN"
    }
    NOTIFICATION {
        string id PK
        string userId FK
        string tytul
        string opis
        enum typ "sukces | finanse | ostrzezenie"
        datetime createdAt
    }
```

## Mapping: OpenAPI schema -> entity

| OpenAPI schema | Entity / note |
| --- | --- |
| `UserProfile`, `RegisterRequest`, `SwitchRole*` | `USER` |
| `Vehicle`, `AddVehicleRequest` | `VEHICLE` |
| `ParkingSpot`, `CreateSpotRequest`, `Coordinates`, `OwnerContact` | `PARKING_SPOT` (+ `SPOT_PHOTO`); `OwnerContact` is a projection of `USER` |
| `ToggleRealtimeStatus*`, `OwnerSpaceItem` | views of `PARKING_SPOT.statusRealTime/status` |
| `SpotCalendarResponse`, `BlockDate*` | `BLOCKED_DATE` + computed day status from `RESERVATION` |
| `CalculateReservation*` | no entity, computed (`stawka * hours + oplataSerwisowa`) |
| `CreateReservationRequest`, `ReservationConfirmedResponse`, `CancelReservationResponse` | `RESERVATION` (+ `PAYMENT`) |
| `ActiveSession`, `CheckOutResponse` | `PARKING_SESSION` |
| `DriverDashboardResponse`, `UpcomingReservationItem`, `HistoryItem` | views over `RESERVATION` / `PARKING_SESSION` |
| `OwnerDashboardResponse`, `OwnerNotificationItem` | views over `PARKING_SPOT` / `RESERVATION`; `NOTIFICATION` |
| `ErrorResponse`, `AuthResponse` | transport only |

## Gaps and inconsistencies in the contract

- Reservation status enum is `OPLACONA` in `ReservationConfirmedResponse` but `OPŁACONA` (with diacritic) in `UpcomingReservationItem`.
- `ParkingSpot.udogodnienia` enum has `Ładowarka EV`, while `CreateSpotRequest` examples use `EV Charging`.
- `ParkingSpot.status` is English machine values while `OwnerSpaceItem.status` and `ToggleRealtimeStatusResponse.status` are free Polish strings.
- Reviews are implied by `ocena`/`liczbaOpinii` but no review schema exists, so `REVIEW` is missing.
- Premium (F08) is only in the endpoint list, with no schema. `premiumActive` and `premiumExpiresAt` on `USER` are inferred.
- `kodPin` is exposed on `ParkingSpot` for all readers. It should only be returned for confirmed reservations.
- IDs are free-form strings (`usr-101`, `spot-42`, `RSV-...`). The backend should decide between UUIDs and a display code.
