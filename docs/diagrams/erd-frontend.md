# Frontend ERD (client-side view models)

Data shapes used by the React frontend screens (API DTOs plus local UI state). Field names follow the frontend contract; the mapping to database columns is in Confluence: *PARI data model (ERD): backend and frontend*.

```mermaid
erDiagram
    AUTH_STATE {
        string token "JWT, local"
        string activeRole "Kierowca|Wlasciciel"
    }
    USER_PROFILE {
        string id
        string email
        string imie
        string nazwisko
        string telefon
        string[] roles
        number balance "not in plan"
        int loyaltyPoints "not in plan"
        boolean premiumActive
    }
    VEHICLE {
        string id
        string model
        string nrRejestracyjny
    }
    SEARCH_FILTERS {
        string city "local"
        number lat "local"
        number lng "local"
        number radius "local"
        datetime startTime "local"
        datetime endTime "local"
        boolean onlyVerified "local"
        number maxPrice "local"
    }
    SPOT_CARD {
        string id
        string nazwa
        string adres
        number lat
        number lng
        number cenaZaGodzine
        string status
        boolean czyZweryfikowane
        number ocena
        string zdjecie
        string[] udogodnienia
    }
    SPOT_DETAIL {
        string id
        object wymiary "length,width,height"
        string opis
        string zasady
        string wlascicielImie
        boolean statusRealTime
        boolean autoAccept
    }
    REVIEW {
        string id
        number ocena
        string komentarz
        string autor
        date data
    }
    ADD_SPOT_FORM {
        object adres "local draft"
        object wymiary "local draft"
        number cena "local draft"
        string[] tagi "local draft"
        file[] zdjecia "multipart"
        boolean oswiadczeniePrawne
        boolean zglosDoWeryfikacji
    }
    CALENDAR_MONTH {
        string spotId
        string month "YYYY-MM"
    }
    CALENDAR_DAY {
        int day
        string status "available|booked|blocked"
        object[] reservations
    }
    BOOKING_DRAFT {
        string spotId "local"
        string vehiclePlate "local"
        datetime startTime "local"
        datetime endTime "local"
        string paymentMethodId "local"
    }
    PRICE_QUOTE {
        number durationHours
        number spotCost
        number serviceFee
        number totalCost
    }
    RESERVATION {
        string id "RSV-..."
        string status "POTWIERDZONA|W_TRAKCIE|ZAKONCZONA|ANULOWANA"
        string pinCode
        string qrData
        number totalCost
        datetime startTime
        datetime endTime
        string address
        object contact
    }
    ACTIVE_SESSION {
        string id
        string spotName
        string address
        datetime checkInTime
        number currentCost
        number hourlyRate
        object vehicle
    }
    DRIVER_DASHBOARD {
        object activeSession
        object[] upcoming
        object[] history
        number savings "not in plan"
        int loyaltyPoints "not in plan"
    }
    OWNER_DASHBOARD {
        number earningsMonth
        object[] chartData
        object ongoingBooking
        object[] mySpots
        object[] notifications
    }
    NOTIFICATION {
        string id
        string type
        string text
        datetime readAt
    }

    AUTH_STATE ||--|| USER_PROFILE : holds
    USER_PROFILE ||--o{ VEHICLE : owns
    SEARCH_FILTERS ||--o{ SPOT_CARD : returns
    SPOT_CARD ||--|| SPOT_DETAIL : expands_to
    SPOT_DETAIL ||--o{ REVIEW : shows
    ADD_SPOT_FORM ||--o| SPOT_DETAIL : creates
    SPOT_DETAIL ||--o{ CALENDAR_MONTH : availability
    CALENDAR_MONTH ||--o{ CALENDAR_DAY : contains
    BOOKING_DRAFT }o--|| SPOT_DETAIL : books
    BOOKING_DRAFT }o--|| VEHICLE : uses
    BOOKING_DRAFT ||--|| PRICE_QUOTE : priced_by
    BOOKING_DRAFT ||--o| RESERVATION : confirmed_as
    RESERVATION ||--o| ACTIVE_SESSION : while_in_progress
    DRIVER_DASHBOARD ||--o| ACTIVE_SESSION : shows
    DRIVER_DASHBOARD ||--o{ RESERVATION : lists
    OWNER_DASHBOARD ||--o{ SPOT_CARD : my_spots
    OWNER_DASHBOARD ||--o{ NOTIFICATION : shows
    USER_PROFILE ||--o{ NOTIFICATION : receives
```
