-- PARI-62: one vehicle can be in only one reservation at a time, at any spot.
-- Same statuses and half-open interval as reservations_no_overlap (which protects the spot); this one protects the
-- vehicle. The database enforces it, so two simultaneous bookings cannot both succeed.
-- Fails if existing data already has overlapping reservations of one vehicle: fix or cancel those rows first.
ALTER TABLE reservations
    ADD CONSTRAINT reservations_vehicle_no_overlap
        EXCLUDE USING gist (vehicle_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
        WHERE (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS'));
