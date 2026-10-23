-- PARI-19: an owner can remove a space. It is archived, not deleted, so reservations, payments and reviews keep their reference.
ALTER TABLE parking_spots DROP CONSTRAINT parking_spots_status_check;
ALTER TABLE parking_spots ADD CONSTRAINT parking_spots_status_check
    CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'BLOCKED', 'ARCHIVED'));
