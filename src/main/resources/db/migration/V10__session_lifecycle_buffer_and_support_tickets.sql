-- PARI-67, PARI-69, PARI-70, PARI-72, PARI-73 (docs/adr/0005): buffer, no-show, auto check-out, support tickets, settings (PARI-68).

-- ---------------------------------------------------------------- reservations

ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED',
                      'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'NO_SHOW'));

-- blocked_until = ends_at + the unpaid buffer. Existing rows keep blocked_until = ends_at: the new rules apply to new reservations only.
ALTER TABLE reservations ADD COLUMN blocked_until timestamptz;
UPDATE reservations SET blocked_until = ends_at;
ALTER TABLE reservations ALTER COLUMN blocked_until SET NOT NULL;
ALTER TABLE reservations ADD CONSTRAINT reservations_blocked_until_check CHECK (blocked_until >= ends_at);

ALTER TABLE reservations ADD COLUMN auto_checked_out boolean NOT NULL DEFAULT false;
-- an overstay penalty that is not charged yet because a driver ticket (CANNOT_LEAVE) is open
ALTER TABLE reservations ADD COLUMN penalty_pending boolean NOT NULL DEFAULT false;
-- bit mask of the session reminders already sent: 1 = E-15, 2 = E, 4 = E+45
ALTER TABLE reservations ADD COLUMN reminders_sent integer NOT NULL DEFAULT 0;

-- Legacy rows: a CONFIRMED reservation that ended and was never used becomes NO_SHOW (no charge, no notification).
UPDATE reservations SET status = 'NO_SHOW' WHERE status = 'CONFIRMED' AND ends_at <= now();

-- The spot is held until blocked_until. Keep the status list equal to ReservationStatus.SLOT_HOLDING and to the
-- native search query in ParkingSpotRepository. The vehicle constraint (V9) stays on the paid period.
ALTER TABLE reservations DROP CONSTRAINT reservations_no_overlap;
ALTER TABLE reservations ADD CONSTRAINT reservations_no_overlap
    EXCLUDE USING gist (spot_id WITH =, tstzrange(starts_at, blocked_until, '[)') WITH &&)
    WHERE (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS'));

-- Raw SQL inserts (seed scripts, manual fixes) may leave blocked_until out: it then defaults to the reserved end.
CREATE FUNCTION reservations_default_blocked_until() RETURNS trigger AS $$
BEGIN
    IF NEW.blocked_until IS NULL THEN
        NEW.blocked_until := NEW.ends_at;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER reservations_default_blocked_until BEFORE INSERT ON reservations
    FOR EACH ROW EXECUTE FUNCTION reservations_default_blocked_until();

-- Recreate the vehicle constraint (V9) after the spot one, so a conflict on both still reports the spot first, as before.
ALTER TABLE reservations DROP CONSTRAINT reservations_vehicle_no_overlap;
ALTER TABLE reservations ADD CONSTRAINT reservations_vehicle_no_overlap
    EXCLUDE USING gist (vehicle_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
    WHERE (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS'));

-- ---------------------------------------------------------------- tickets (extend reports)

ALTER TABLE reports ADD COLUMN type varchar(20) NOT NULL DEFAULT 'OTHER'
    CHECK (type IN ('VEHICLE_NOT_LEFT', 'CANNOT_LEAVE', 'OTHER'));
ALTER TABLE reports ADD COLUMN source varchar(10) NOT NULL DEFAULT 'DRIVER'
    CHECK (source IN ('OWNER', 'DRIVER', 'SYSTEM'));
ALTER TABLE reports ADD COLUMN priority varchar(10) NOT NULL DEFAULT 'NORMAL'
    CHECK (priority IN ('NORMAL', 'HIGH'));
-- a SYSTEM ticket has no reporter
ALTER TABLE reports ALTER COLUMN reporter_id DROP NOT NULL;

ALTER TABLE reports ADD COLUMN evacuation_status varchar(15)
    CHECK (evacuation_status IN ('REQUESTED', 'IN_PROGRESS', 'DONE'));
ALTER TABLE reports ADD COLUMN evacuation_destination varchar(255);
ALTER TABLE reports ADD COLUMN evacuation_fee_minor bigint CHECK (evacuation_fee_minor >= 0);

-- one open VEHICLE_NOT_LEFT ticket per reservation
CREATE UNIQUE INDEX reports_one_open_vehicle_not_left
    ON reports (reservation_id)
    WHERE type = 'VEHICLE_NOT_LEFT' AND status IN ('OPEN', 'IN_PROGRESS');
CREATE INDEX reports_queue_idx ON reports (status, priority, created_at);

-- ---------------------------------------------------------------- settings

-- evacuation_fee is a PLACEHOLDER (the team has not decided the amount yet); the other values are the ones agreed in the design.
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('reservation_buffer_minutes',      '60',   'Unpaid minutes after the reserved end during which the spot stays blocked for new bookings (PARI-69)'),
    ('auto_checkout_after_end_minutes', '60',   'Minutes after the reserved end when a running session is ended automatically (PARI-67)'),
    ('auto_ticket_after_end_minutes',   '15',   'Minutes after the reserved end when the system opens a VEHICLE_NOT_LEFT ticket for a running session (PARI-70)'),
    ('reminder_before_end_minutes',     '15',   'Minutes before the reserved end of the first session reminder (PARI-73)'),
    ('reminder_final_after_end_minutes','45',   'Minutes after the reserved end of the final session reminder (PARI-73)'),
    ('evacuation_fee',                  '0.00', 'PLN charged to the driver for an evacuation (PARI-72). PLACEHOLDER');
