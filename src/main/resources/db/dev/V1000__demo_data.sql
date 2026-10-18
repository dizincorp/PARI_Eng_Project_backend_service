-- DEMO DATA for development and manual testing. NOT part of the real schema history.
-- Loaded only when spring.flyway.locations also contains classpath:db/dev (local profile / docker compose).
-- Fixed ids so they can be pasted into Swagger "Authorize" (X-User-Id) and into requests.
--
--   Owner  : 00000000-0000-0000-0000-000000000001  (anna.owner@pari.test)
--   Driver : 00000000-0000-0000-0000-000000000002  (jan.driver@pari.test)
--   Both   : 00000000-0000-0000-0000-000000000003  (ola.both@pari.test)
--   Vehicle of the driver: 00000000-0000-0000-0000-0000000000a1
-- Booking: payment method token "pm_card_visa" succeeds, "pm_card_chargeDeclined" is declined (mock gateway).
-- There is no login yet; password_hash is a placeholder, not a real hash.

INSERT INTO users (id, email, password_hash, first_name, last_name, email_verified, phone_verified, active_role) VALUES
    ('00000000-0000-0000-0000-000000000001', 'anna.owner@pari.test',  'dev-only-not-a-real-hash', 'Anna', 'Owner',  true, true, 'OWNER'),
    ('00000000-0000-0000-0000-000000000002', 'jan.driver@pari.test',  'dev-only-not-a-real-hash', 'Jan',  'Driver', true, true, 'DRIVER'),
    ('00000000-0000-0000-0000-000000000003', 'ola.both@pari.test',    'dev-only-not-a-real-hash', 'Ola',  'Both',   true, true, 'DRIVER');

INSERT INTO user_roles (user_id, role) VALUES
    ('00000000-0000-0000-0000-000000000001', 'OWNER'),
    ('00000000-0000-0000-0000-000000000002', 'DRIVER'),
    ('00000000-0000-0000-0000-000000000003', 'DRIVER'),
    ('00000000-0000-0000-0000-000000000003', 'OWNER');

INSERT INTO vehicles (id, user_id, model, plate_number) VALUES
    ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000002', 'Skoda Octavia', 'LU 12345'),
    ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000003', 'Toyota Yaris',  'LU 67890');

INSERT INTO payment_methods (id, user_id, provider_ref, brand, last4, exp_month, exp_year, is_default) VALUES
    ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-000000000002', 'pm_card_visa',       'visa', '4242', 12, 2030, true),
    ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-000000000003', 'pm_card_mastercard', 'mastercard', '4444', 12, 2030, true);

-- Spots around Lublin city centre. Prices are in grosze (1200 = 12.00 PLN per hour).
INSERT INTO parking_spots (id, owner_id, name, description, street, city, postal_code, lat, lng,
                           length_cm, width_cm, height_cm, hourly_price_minor, access_instructions,
                           auto_accept, status, is_verified, ownership_declared_at) VALUES
    ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001',
     'Garage near Old Town', 'Covered garage, 5 minutes from the Old Town.', 'Lubartowska 12', 'Lublin', '20-001',
     51.2486, 22.5684, 520, 260, 210, 1200, 'Gate code 1234, space number 7.', true, 'ACTIVE', true, now()),
    ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001',
     'Courtyard space, Krakowskie Przedmiescie', 'Open courtyard space behind a gate.', 'Krakowskie Przedmiescie 40', 'Lublin', '20-002',
     51.2480, 22.5640, 500, 250, NULL, 1500, 'Call the owner on arrival.', false, 'ACTIVE', false, now()),
    ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000003',
     'Space with EV charger near UMCS', 'Wide space with a 7 kW charger.', 'Radziszewskiego 17', 'Lublin', '20-031',
     51.2465, 22.5430, 560, 300, 220, 900, 'Park in the marked bay.', true, 'ACTIVE', true, now()),
    ('00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-000000000003',
     'Cheap space, Czechow', 'Simple outdoor space.', 'Nadbystrzycka 30', 'Lublin', '20-618',
     51.2290, 22.5190, 480, 240, NULL, 500, NULL, true, 'ACTIVE', false, now()),
    ('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-000000000001',
     'Draft space (not published)', 'Used to test activation.', 'Zana 5', 'Lublin', '20-601',
     51.2350, 22.5100, 500, 250, NULL, 800, NULL, true, 'DRAFT', false, NULL);

INSERT INTO spot_amenities (spot_id, amenity_id)
SELECT s.spot_id, a.id
FROM (VALUES
    ('00000000-0000-0000-0000-0000000000c1'::uuid, 'COVERED'),
    ('00000000-0000-0000-0000-0000000000c1'::uuid, 'GATE'),
    ('00000000-0000-0000-0000-0000000000c1'::uuid, 'CCTV'),
    ('00000000-0000-0000-0000-0000000000c2'::uuid, 'GATE'),
    ('00000000-0000-0000-0000-0000000000c2'::uuid, 'LIGHTING'),
    ('00000000-0000-0000-0000-0000000000c3'::uuid, 'EV_CHARGER'),
    ('00000000-0000-0000-0000-0000000000c3'::uuid, 'WIDE_SPACE'),
    ('00000000-0000-0000-0000-0000000000c3'::uuid, 'LIGHTING')
) AS s (spot_id, code)
JOIN amenities a ON a.code = s.code;

-- Weekly availability: every day 06:00-22:00 (spot owner's local time), for all active spots.
INSERT INTO availability_rules (id, spot_id, day_of_week, start_time, end_time)
SELECT gen_random_uuid(), p.id, d, TIME '06:00', TIME '22:00'
FROM parking_spots p CROSS JOIN generate_series(1, 7) AS d
WHERE p.status = 'ACTIVE';
