-- PARI-55: an owner can set a price for a whole day and a price for a whole month on a space.
-- NULL means "not set": a daily or monthly booking is then priced from the hourly price, as before (PARI-46).
-- The prices are the owner's own input, no platform value is involved.
ALTER TABLE parking_spots ADD COLUMN daily_price_minor   bigint CHECK (daily_price_minor > 0);
ALTER TABLE parking_spots ADD COLUMN monthly_price_minor bigint CHECK (monthly_price_minor > 0);
