-- PARI-46: progressive discount for monthly bookings, by the number of months booked at once.
-- PLACEHOLDER business values: the Jira ticket gives them only as an example ("e.g. 3 months = -5%, 6 months = -10%,
-- 12 months = -15%") and the design document defines none. Replace them with the values the team agrees on (changeable at runtime).
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('discount_3_months_percent',  '5',  'Discount in percent for a monthly booking of 3 to 5 months. PLACEHOLDER from the PARI-46 example, not agreed'),
    ('discount_6_months_percent',  '10', 'Discount in percent for a monthly booking of 6 to 11 months. PLACEHOLDER from the PARI-46 example, not agreed'),
    ('discount_12_months_percent', '15', 'Discount in percent for a monthly booking of 12 months or more. PLACEHOLDER from the PARI-46 example, not agreed');
