-- PARI-55: multimodal pricing (V7 added the owner's daily and monthly price; here they get the names used in the ticket).
ALTER TABLE parking_spots RENAME COLUMN daily_price_minor   TO daily_rate_minor;
ALTER TABLE parking_spots RENAME COLUMN monthly_price_minor TO monthly_rate_minor;

-- 2. What a reservation was priced as, so a booking can be explained later.
ALTER TABLE reservations ADD COLUMN booking_type    varchar(20) NOT NULL DEFAULT 'HOURLY'
    CHECK (booking_type IN ('HOURLY', 'DAILY', 'MONTHLY'));
ALTER TABLE reservations ADD COLUMN duration_days   integer CHECK (duration_days > 0);
ALTER TABLE reservations ADD COLUMN duration_months integer CHECK (duration_months > 0);
ALTER TABLE reservations ADD COLUMN base_cost_minor bigint  CHECK (base_cost_minor >= 0);   -- before the long-term discount
ALTER TABLE reservations ADD COLUMN discount_minor  bigint  NOT NULL DEFAULT 0 CHECK (discount_minor >= 0);
-- Earlier reservations were all priced by the hour and had no discount.
UPDATE reservations SET base_cost_minor = spot_cost_minor;

-- 3. Service fee per rental type and the default rates (read through PlatformSettings).
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('service_fee_hourly_min_minor',    '250', 'Minimum service fee of an hourly booking in grosze: max(this, service_fee_rate x spot cost) (PARI-55)'),
    ('service_fee_daily_minor',         '250', 'Fixed service fee of a daily booking in grosze (PARI-55)'),
    ('service_fee_monthly_minor',       '500', 'Fixed service fee of a monthly booking in grosze (PARI-55)'),
    ('daily_rate_hourly_multiplier',    '7',   'A space without its own daily rate costs this many hours per day (PARI-55)'),
    ('monthly_rate_daily_multiplier',   '20',  'A space without its own monthly rate costs this many days per month (PARI-55)');

-- The ticket confirms the percentages that PARI-23/46 had as placeholders.
UPDATE platform_settings SET description = 'Share of the spot cost an hourly booking pays as service fee, never below service_fee_hourly_min_minor (PARI-55)'
    WHERE setting_key = 'service_fee_rate';
UPDATE platform_settings SET description = 'Discount in percent for a monthly booking of 3 to 5 months (PARI-55)' WHERE setting_key = 'discount_3_months_percent';
UPDATE platform_settings SET description = 'Discount in percent for a monthly booking of 6 to 11 months (PARI-55)' WHERE setting_key = 'discount_6_months_percent';
UPDATE platform_settings SET description = 'Discount in percent for a monthly booking of 12 months or more (PARI-55)' WHERE setting_key = 'discount_12_months_percent';
