-- PARI-65 (docs/adr/0006), part 3: blocked periods per space and occupancy per space.

-- A blocked period with a unit applies to that space only; without a unit it closes every space of the listing.
-- Extra open periods (kind OPEN) stay listing-wide.
ALTER TABLE availability_exceptions ADD COLUMN unit_id uuid REFERENCES spot_units (id);
ALTER TABLE availability_exceptions ADD CONSTRAINT availability_exceptions_unit_blocked_only
    CHECK (unit_id IS NULL OR kind = 'BLOCKED');
CREATE INDEX availability_exceptions_unit_idx ON availability_exceptions (unit_id, starts_at) WHERE unit_id IS NOT NULL;

-- Occupancy (FREE/OCCUPIED, changed by check-in and check-out) moves to the space. parking_spots.occupancy stays and
-- is kept in step by the application: OCCUPIED only when every live space is occupied.
ALTER TABLE spot_units ADD COLUMN occupancy varchar(20) NOT NULL DEFAULT 'FREE' CHECK (occupancy IN ('FREE', 'OCCUPIED'));
UPDATE spot_units u SET occupancy = s.occupancy FROM parking_spots s WHERE s.id = u.spot_id;
