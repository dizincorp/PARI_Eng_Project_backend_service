-- PARI-65 (docs/adr/0006): independently bookable spaces (units) within one listing, part 1: schema, backfill and the
-- no-overlap constraint on the unit.

CREATE TABLE spot_units (
    id          uuid         PRIMARY KEY,
    spot_id     uuid         NOT NULL REFERENCES parking_spots (id) ON DELETE CASCADE,
    label       varchar(20)  NOT NULL CHECK (btrim(label) <> ''),
    -- creation order inside the listing; it decides which unit is the "first" one, and is never reused
    ordinal     integer      NOT NULL CHECK (ordinal > 0),
    -- a removed unit stays so old reservations keep their unit and label
    removed_at  timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT spot_units_ordinal_uq UNIQUE (spot_id, ordinal)
);
-- the label identifies a space for the owner and the driver, so two live units of a listing cannot share one
CREATE UNIQUE INDEX spot_units_live_label_uq ON spot_units (spot_id, label) WHERE removed_at IS NULL;

-- every existing listing is one space: its default unit is #1
INSERT INTO spot_units (id, spot_id, label, ordinal)
SELECT gen_random_uuid(), id, '#1', 1 FROM parking_spots;

-- ---------------------------------------------------------------- reservations

ALTER TABLE reservations ADD COLUMN unit_id uuid REFERENCES spot_units (id);
-- the label as it was when the reservation was made, so a later rename does not rewrite receipts
ALTER TABLE reservations ADD COLUMN unit_label varchar(20);

UPDATE reservations r SET unit_id = u.id, unit_label = u.label
FROM spot_units u WHERE u.spot_id = r.spot_id AND u.ordinal = 1;

ALTER TABLE reservations ALTER COLUMN unit_id SET NOT NULL;
ALTER TABLE reservations ALTER COLUMN unit_label SET NOT NULL;
CREATE INDEX reservations_unit_idx ON reservations (unit_id, starts_at);

-- Raw SQL inserts (seed scripts, manual fixes) may leave the unit out: it then defaults to the first live unit of the spot.
CREATE FUNCTION reservations_default_unit() RETURNS trigger AS $$
BEGIN
    IF NEW.unit_id IS NULL THEN
        SELECT u.id, u.label INTO NEW.unit_id, NEW.unit_label
        FROM spot_units u WHERE u.spot_id = NEW.spot_id AND u.removed_at IS NULL
        ORDER BY u.ordinal LIMIT 1;
    ELSIF NEW.unit_label IS NULL THEN
        SELECT u.label INTO NEW.unit_label FROM spot_units u WHERE u.id = NEW.unit_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER reservations_default_unit BEFORE INSERT ON reservations
    FOR EACH ROW EXECUTE FUNCTION reservations_default_unit();

-- The unit is the thing that is held. Keep the status list equal to ReservationStatus.SLOT_HOLDING and to the native search
-- query in ParkingSpotRepository. The vehicle constraint (V9) stays on the paid period.
ALTER TABLE reservations DROP CONSTRAINT reservations_no_overlap;
ALTER TABLE reservations ADD CONSTRAINT reservations_no_overlap
    EXCLUDE USING gist (unit_id WITH =, tstzrange(starts_at, blocked_until, '[)') WITH &&)
    WHERE (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS'));

-- Recreate the vehicle constraint (V9) after the unit one, so a conflict on both still reports the space first, as before.
ALTER TABLE reservations DROP CONSTRAINT reservations_vehicle_no_overlap;
ALTER TABLE reservations ADD CONSTRAINT reservations_vehicle_no_overlap
    EXCLUDE USING gist (vehicle_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
    WHERE (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PROGRESS'));

-- ---------------------------------------------------------------- settings

-- PLACEHOLDER: the team has not decided the maximum number of spaces in one listing.
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('max_units_per_spot', '50', 'Maximum number of bookable spaces (units) in one listing (PARI-65). PLACEHOLDER');
