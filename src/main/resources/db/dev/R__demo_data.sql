-- DEMO DATA for development and manual testing. NOT part of the real schema history.
--
-- Loaded only when spring.flyway.locations also contains classpath:db/dev (profile "seed", the "seeded" Docker variant).
-- The default (clean) variant leaves db/dev out and gets the schema plus the reference data of the migrations only
-- (platform_settings and amenities).
--
-- This is a repeatable migration (R__): Flyway runs it after every versioned migration, so a new real migration
-- (V7, V8, ...) never conflicts with it. It runs once: when the demo users already exist it does nothing, so data
-- that testers created is never overwritten. To get a changed version of this file into an existing database,
-- recreate the database (docker compose down -v).
--
-- Consistency rules (the database and the services both rely on them; DemoDataTest checks them):
--   * every amount is derived from the spot price and from platform_settings, the way PricingService does it:
--     service fee and commission are shares of the spot cost; the owner of Ola's spots has Premium, so no commission
--   * every paid reservation has a succeeded CHARGE and a payout; a refunded one has a REFUND and a CANCELLED payout;
--     a failed payment has no payout
--   * a payout is PAID when its release_at is in the past, SCHEDULED otherwise, CANCELLED after a refund
--   * a spot is verified only when it has an APPROVED verification request; its rating is the average of its reviews
--   * a spot is OCCUPIED only while a reservation of it is IN_PROGRESS
--   * reservations that hold a slot never overlap, neither per space nor per vehicle; future ones lie inside the weekly availability (06:00-22:00)
--   * times are relative to the day the database is created, in Europe/Warsaw
--
-- Fixed ids, so they can be pasted into requests:
--   Owner    00000000-0000-0000-0000-000000000001  anna.owner@pari.test
--   Driver   00000000-0000-0000-0000-000000000002  jan.driver@pari.test
--   Both     00000000-0000-0000-0000-000000000003  ola.both@pari.test   (owner with an active Premium plan, also a driver)
--   Verifier 00000000-0000-0000-0000-000000000004  vera.verifier@pari.test
--   Password of every demo user: Demo1234!
--   Cards: pm_card_visa (Jan), pm_card_mastercard (Ola) succeed; pm_card_chargeDeclined is declined (mock gateway).
--
-- Known limits: no phone numbers, withdrawals or spot photos are seeded, because phones and IBANs are encrypted with
-- the application key, which this script does not know, and photos need files in the media directory.
-- The pending request (RSV-...-D0007) expires like any other after approval_timeout_minutes (the scheduler does it).

CREATE OR REPLACE FUNCTION pg_temp.local_ts(days integer, hours numeric) RETURNS timestamptz
    LANGUAGE sql STABLE AS
$f$ SELECT ((date_trunc('day', now() AT TIME ZONE 'Europe/Warsaw') + days * interval '1 day' + hours * interval '1 hour')
            AT TIME ZONE 'Europe/Warsaw') $f$;

DO
$seed$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE id = '00000000-0000-0000-0000-000000000001') THEN
        RAISE NOTICE 'PARI demo data already present, skipping';
        RETURN;
    END IF;

    -- ------------------------------------------------------------ users, vehicles, cards

    INSERT INTO users (id, email, password_hash, first_name, last_name, email_verified, phone_verified, active_role) VALUES
        ('00000000-0000-0000-0000-000000000001', 'anna.owner@pari.test',    '$2a$10$UZrWcnpsGnnYqXMeABhUaezi6sZ2YTeZdrEoAPpNVi4F5iFzcbIj.', 'Anna', 'Owner',    true, true, 'OWNER'),
        ('00000000-0000-0000-0000-000000000002', 'jan.driver@pari.test',    '$2a$10$UZrWcnpsGnnYqXMeABhUaezi6sZ2YTeZdrEoAPpNVi4F5iFzcbIj.', 'Jan',  'Driver',   true, true, 'DRIVER'),
        ('00000000-0000-0000-0000-000000000003', 'ola.both@pari.test',      '$2a$10$UZrWcnpsGnnYqXMeABhUaezi6sZ2YTeZdrEoAPpNVi4F5iFzcbIj.', 'Ola',  'Both',     true, true, 'DRIVER'),
        ('00000000-0000-0000-0000-000000000004', 'vera.verifier@pari.test', '$2a$10$UZrWcnpsGnnYqXMeABhUaezi6sZ2YTeZdrEoAPpNVi4F5iFzcbIj.', 'Vera', 'Verifier', true, true, 'VERIFIER');

    INSERT INTO user_roles (user_id, role) VALUES
        ('00000000-0000-0000-0000-000000000001', 'OWNER'),
        ('00000000-0000-0000-0000-000000000002', 'DRIVER'),
        ('00000000-0000-0000-0000-000000000003', 'DRIVER'),
        ('00000000-0000-0000-0000-000000000003', 'OWNER'),
        ('00000000-0000-0000-0000-000000000004', 'VERIFIER');

    INSERT INTO vehicles (id, user_id, model, plate_number) VALUES
        ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-000000000002', 'Skoda Octavia', 'LU 12345'),
        ('00000000-0000-0000-0000-0000000000a2', '00000000-0000-0000-0000-000000000003', 'Toyota Yaris',  'LU 67890'),
        -- a second car of Jan: his running session D0005 and his future bookings cannot share one vehicle (PARI-62)
        ('00000000-0000-0000-0000-0000000000a3', '00000000-0000-0000-0000-000000000002', 'Honda Civic',   'LU 24680');

    INSERT INTO payment_methods (id, user_id, provider_ref, brand, last4, exp_month, exp_year, is_default) VALUES
        ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-000000000002', 'pm_card_visa',       'visa',       '4242', 12, 2030, true),
        ('00000000-0000-0000-0000-0000000000b2', '00000000-0000-0000-0000-000000000003', 'pm_card_mastercard', 'mastercard', '4444', 12, 2030, true);

    -- Ola's Premium plan started 6 days ago, so every reservation of her spots in this data has no commission.
    INSERT INTO premium_subscriptions (id, owner_id, plan, status, started_at, expires_at, provider_ref, created_at)
    VALUES ('00000000-0000-0000-0000-0000000000f1', '00000000-0000-0000-0000-000000000003', 'PREMIUM_MONTHLY', 'ACTIVE',
            now() - interval '6 days', now() - interval '6 days' + interval '1 month',
            'mock_sub_' || md5('demo-premium')::uuid, now() - interval '6 days');

    -- ------------------------------------------------------------ spots

    -- Around Lublin city centre. Prices are in grosze (1200 = 12.00 PLN per hour). Rating and review count are set below.
    INSERT INTO parking_spots (id, owner_id, name, description, street, city, postal_code, lat, lng,
                               length_cm, width_cm, height_cm, hourly_price_minor, access_instructions,
                               auto_accept, status, is_verified, ownership_declared_at, occupancy) VALUES
        ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001',
         'Garage near Old Town', 'Covered garage, 5 minutes from the Old Town.', 'Lubartowska 12', 'Lublin', '20-001',
         51.2486, 22.5684, 520, 260, 210, 1200, 'Gate code 1234, space number 7.', true, 'ACTIVE', true, now() - interval '30 days', 'FREE'),
        ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001',
         'Courtyard space, Krakowskie Przedmiescie', 'Open courtyard space behind a gate. The owner approves every request.', 'Krakowskie Przedmiescie 40', 'Lublin', '20-002',
         51.2480, 22.5640, 500, 250, NULL, 1500, 'Call the owner on arrival.', false, 'ACTIVE', false, now() - interval '30 days', 'FREE'),
        ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000003',
         'Space with EV charger near UMCS', 'Wide space with a 7 kW charger.', 'Radziszewskiego 17', 'Lublin', '20-031',
         51.2465, 22.5430, 560, 300, 220, 900, 'Park in the marked bay.', true, 'ACTIVE', true, now() - interval '30 days', 'FREE'),
        ('00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-000000000003',
         'Cheap space, Czechow', 'Simple outdoor space.', 'Nadbystrzycka 30', 'Lublin', '20-618',
         51.2290, 22.5190, 480, 240, NULL, 500, NULL, true, 'ACTIVE', false, now() - interval '30 days', 'OCCUPIED'),
        ('00000000-0000-0000-0000-0000000000c5', '00000000-0000-0000-0000-000000000001',
         'Draft space (not published)', 'Used to test activation.', 'Zana 5', 'Lublin', '20-601',
         51.2350, 22.5100, 500, 250, NULL, 800, NULL, true, 'DRAFT', false, NULL, 'FREE');

    -- PARI-55: two spaces have their own daily and monthly rate (the others are priced from the hourly price).
    UPDATE parking_spots SET daily_rate_minor = 18000, monthly_rate_minor = 300000 WHERE id = '00000000-0000-0000-0000-0000000000c1';
    UPDATE parking_spots SET daily_rate_minor = 15000, monthly_rate_minor = 250000 WHERE id = '00000000-0000-0000-0000-0000000000c3';

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

    -- Weekly availability: every day 06:00-22:00 (the owner's local time) for every active spot.
    INSERT INTO availability_rules (id, spot_id, day_of_week, start_time, end_time)
    SELECT gen_random_uuid(), p.id, d, TIME '06:00', TIME '22:00'
    FROM parking_spots p CROSS JOIN generate_series(1, 7) AS d
    WHERE p.status = 'ACTIVE';

    INSERT INTO availability_exceptions (id, spot_id, starts_at, ends_at, kind, reason) VALUES
        (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', pg_temp.local_ts(3, 8), pg_temp.local_ts(3, 18), 'BLOCKED', 'The owner uses the space');

    -- Verification: verified spots have an approved request, the others a request in the queue or a rejected one.
    INSERT INTO verification_requests (id, spot_id, requested_by, verifier_id, status, rejection_reason, notes, requested_at, decided_at) VALUES
        (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000004',
         'APPROVED', NULL, 'Documents and the space checked on site.', now() - interval '28 days', now() - interval '27 days'),
        (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004',
         'APPROVED', NULL, 'Documents and the space checked on site.', now() - interval '28 days', now() - interval '26 days'),
        (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000001', NULL,
         'REQUESTED', NULL, NULL, now() - interval '2 days', NULL),
        (gen_random_uuid(), '00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000004',
         'REJECTED', 'The proof of ownership is not readable. Send a clearer photo.', NULL, now() - interval '10 days', now() - interval '9 days');

    -- ------------------------------------------------------------ reservations

    -- One row per state. over_min is how long after the end the driver left (a penalty applies above the grace period).
    -- D0001 completed, D0002 completed with an overstay penalty, D0003 completed on Ola's Premium space,
    -- D0004 completed and not reviewed yet (its payout is still scheduled), D0005 in progress (daily),
    -- D0006 confirmed, D0007 waiting for the owner, D0008 cancelled by the driver, D0009 rejected by the owner,
    -- D0010 expired, D0011 payment declined.
    WITH cfg AS (
        SELECT (SELECT setting_value::numeric FROM platform_settings WHERE setting_key = 'service_fee_rate')            AS fee_rate,
               (SELECT setting_value::numeric FROM platform_settings WHERE setting_key = 'commission_rate')             AS commission_rate,
               (SELECT setting_value::numeric FROM platform_settings WHERE setting_key = 'overstay_penalty_multiplier') AS penalty_multiplier,
               (SELECT setting_value::int     FROM platform_settings WHERE setting_key = 'overstay_grace_minutes')      AS grace_minutes,
               (SELECT setting_value::int     FROM platform_settings WHERE setting_key = 'approval_timeout_minutes')    AS approval_minutes,
               (SELECT setting_value::int     FROM platform_settings WHERE setting_key = 'service_fee_hourly_min_minor') AS fee_hourly_min,
               (SELECT setting_value::int     FROM platform_settings WHERE setting_key = 'service_fee_daily_minor')     AS fee_daily,
               (SELECT setting_value::numeric FROM platform_settings WHERE setting_key = 'daily_rate_hourly_multiplier') AS daily_multiplier
    ),
    v (n, spot_id, driver_id, vehicle_id, starts, ends, status, over_min, reason, cancel_after_min, created) AS (
        VALUES
        (1,  '00000000-0000-0000-0000-0000000000c1'::uuid, '00000000-0000-0000-0000-000000000002'::uuid, '00000000-0000-0000-0000-0000000000a1'::uuid,
             pg_temp.local_ts(-3, 10), pg_temp.local_ts(-3, 13), 'COMPLETED', 0, NULL::text, NULL::int, NULL::timestamptz),
        (2,  '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             pg_temp.local_ts(-2, 14), pg_temp.local_ts(-2, 16), 'COMPLETED', 40, NULL, NULL, NULL),
        (3,  '00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             pg_temp.local_ts(-4, 9), pg_temp.local_ts(-4, 11), 'COMPLETED', 0, NULL, NULL, NULL),
        (4,  '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000a2',
             now() - interval '210 minutes', now() - interval '120 minutes', 'COMPLETED', 0, NULL, NULL, NULL),
        (5,  '00000000-0000-0000-0000-0000000000c4', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             now() - interval '1 hour', now() + interval '23 hours', 'IN_PROGRESS', 0, NULL, NULL, NULL),
        (6,  '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a3',
             pg_temp.local_ts(1, 10), pg_temp.local_ts(1, 12), 'CONFIRMED', 0, NULL, NULL, NULL),
        (7,  '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a3',
             pg_temp.local_ts(2, 15), pg_temp.local_ts(2, 17), 'PENDING_APPROVAL', 0, NULL, NULL, now()),
        (8,  '00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             pg_temp.local_ts(7, 10), pg_temp.local_ts(7, 12), 'CANCELLED', 0, 'Plans changed', 120, NULL),
        (9,  '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-0000000000a2',
             pg_temp.local_ts(3, 9), pg_temp.local_ts(3, 10), 'REJECTED', 0, 'The space is not available that morning', 30, NULL),
        (10, '00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             pg_temp.local_ts(5, 12), pg_temp.local_ts(5, 14), 'EXPIRED', 0, 'The owner did not respond in time', NULL, NULL),
        (11, '00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-0000000000a1',
             pg_temp.local_ts(4, 11), pg_temp.local_ts(4, 12), 'CANCELLED', 0, 'Payment failed: The card was declined', 0, NULL)
    ),
    calc AS (
        SELECT v.*, s.hourly_price_minor AS rate, s.owner_id, s.daily_rate_minor, cfg.*,
               COALESCE(v.created, LEAST(v.starts - interval '1 day', now() - interval '10 minutes')) AS created_at,
               -- D0005 is the daily booking, every other reservation is hourly
               CASE WHEN v.n = 5 THEN 'DAILY' ELSE 'HOURLY' END AS btype,
               GREATEST(1, (v.ends AT TIME ZONE 'Europe/Warsaw')::date - (v.starts AT TIME ZONE 'Europe/Warsaw')::date) AS days
        FROM v JOIN parking_spots s ON s.id = v.spot_id CROSS JOIN cfg
    ),
    priced AS (
        -- the pricing rules of PricingService (PARI-55): every started hour is paid; a day costs the owner's daily rate
        -- or daily_rate_hourly_multiplier x the hourly price; the service fee is fixed for a day and at least the minimum for an hour
        SELECT calc.*,
               CASE WHEN btype = 'DAILY'
                    THEN days * COALESCE(daily_rate_minor, round(rate * daily_multiplier))
                    ELSE ceil(extract(epoch FROM (ends - starts)) / 3600) * rate END AS spot_cost
        FROM calc
    ),
    fees AS (
        SELECT priced.*,
               CASE WHEN btype = 'DAILY' THEN fee_daily
                    ELSE GREATEST(fee_hourly_min, round(spot_cost * fee_rate)) END AS service_fee
        FROM priced
    )
    INSERT INTO reservations (id, code, spot_id, driver_id, vehicle_id, starts_at, ends_at, status,
                              hourly_rate_minor, spot_cost_minor, service_fee_minor, total_minor, commission_minor,
                              booking_type, duration_days, base_cost_minor, discount_minor,
                              pin_code, qr_token, check_in_at, check_out_at, overstay_penalty_minor, final_cost_minor,
                              cancel_reason, cancelled_at, created_at, updated_at)
    SELECT ('00000000-0000-0000-0000-00000000d0' || lpad(n::text, 2, '0'))::uuid,
           'RSV-' || to_char(created_at AT TIME ZONE 'UTC', 'YYYYMMDD') || '-D' || lpad(n::text, 4, '0'),
           spot_id, driver_id, vehicle_id, starts, ends, status,
           rate, spot_cost,
           service_fee,
           spot_cost + service_fee,
           CASE WHEN owner_id = '00000000-0000-0000-0000-000000000003' THEN 0 ELSE round(spot_cost * commission_rate) END,
           btype, CASE WHEN btype = 'DAILY' THEN days END, spot_cost, 0,
           CASE WHEN status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED') THEN lpad((abs(hashtext('pin' || n)) % 10000)::text, 4, '0') END,
           CASE WHEN status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETED') THEN 'demo-' || substr(md5('qr' || n), 1, 24) END,
           CASE WHEN status IN ('IN_PROGRESS', 'COMPLETED') THEN starts + interval '2 minutes' END,
           CASE WHEN status = 'COMPLETED' THEN CASE WHEN over_min > 0 THEN ends + over_min * interval '1 minute' ELSE ends - interval '3 minutes' END END,
           CASE WHEN status = 'COMPLETED' AND over_min > grace_minutes THEN round(rate * penalty_multiplier * over_min / 60.0) END,
           CASE WHEN status = 'COMPLETED' THEN spot_cost + COALESCE(CASE WHEN over_min > grace_minutes THEN round(rate * penalty_multiplier * over_min / 60.0) END, 0) END,
           reason,
           CASE WHEN status = 'EXPIRED' THEN created_at + approval_minutes * interval '1 minute'
                WHEN cancel_after_min IS NOT NULL THEN created_at + cancel_after_min * interval '1 minute' END,
           created_at, created_at
    FROM fees;

    -- ------------------------------------------------------------ payments and payouts

    -- The charge of every reservation whose payment went through; the declined one is recorded as FAILED.
    INSERT INTO payments (id, reservation_id, type, provider_ref, amount_minor, currency, status, created_at, updated_at)
    SELECT gen_random_uuid(), r.id, 'CHARGE',
           CASE WHEN r.cancel_reason LIKE 'Payment failed%' THEN NULL ELSE 'mock_pi_' || md5('reservation-' || r.id)::uuid END,
           r.total_minor, 'PLN',
           CASE WHEN r.cancel_reason LIKE 'Payment failed%' THEN 'FAILED' ELSE 'SUCCEEDED' END,
           r.created_at + interval '1 minute', r.created_at + interval '1 minute'
    FROM reservations r
    WHERE r.id::text LIKE '00000000-0000-0000-0000-00000000d0%';

    -- The overstay penalty is charged to the driver's saved card.
    INSERT INTO payments (id, reservation_id, type, provider_ref, amount_minor, currency, status, created_at, updated_at)
    SELECT gen_random_uuid(), r.id, 'PENALTY', 'mock_pi_' || md5('penalty-' || r.id)::uuid, r.overstay_penalty_minor, 'PLN',
           'SUCCEEDED', r.check_out_at, r.check_out_at
    FROM reservations r
    WHERE r.id::text LIKE '00000000-0000-0000-0000-00000000d0%' AND r.overstay_penalty_minor > 0;

    -- Rejected, expired and cancelled reservations that had been paid are refunded in full.
    INSERT INTO payments (id, reservation_id, type, provider_ref, amount_minor, currency, status, created_at, updated_at)
    SELECT gen_random_uuid(), r.id, 'REFUND', 'mock_re_' || md5('refund-' || r.id)::uuid, r.total_minor, 'PLN',
           'SUCCEEDED', r.cancelled_at, r.cancelled_at
    FROM reservations r
    WHERE r.id::text LIKE '00000000-0000-0000-0000-00000000d0%'
      AND r.status IN ('CANCELLED', 'REJECTED', 'EXPIRED') AND r.cancel_reason NOT LIKE 'Payment failed%';

    -- Soft escrow (F17): spot cost minus commission, released payout_delay_hours after the end. The overstay
    -- penalty is not part of the payout (the service does not add it either).
    INSERT INTO payouts (id, reservation_id, owner_id, gross_minor, commission_minor, net_minor, status, release_at, paid_at, created_at, updated_at)
    SELECT gen_random_uuid(), r.id, s.owner_id, r.spot_cost_minor, r.commission_minor, r.spot_cost_minor - r.commission_minor,
           CASE WHEN r.status IN ('CANCELLED', 'REJECTED', 'EXPIRED') THEN 'CANCELLED'
                WHEN r.status = 'COMPLETED' AND rel.release_at <= now() THEN 'PAID'
                ELSE 'SCHEDULED' END,
           rel.release_at,
           CASE WHEN r.status = 'COMPLETED' AND rel.release_at <= now() THEN rel.release_at + interval '3 minutes' END,
           r.created_at + interval '1 minute', r.created_at + interval '1 minute'
    FROM reservations r
    JOIN parking_spots s ON s.id = r.spot_id
    CROSS JOIN LATERAL (
        SELECT r.ends_at + (SELECT setting_value::int FROM platform_settings WHERE setting_key = 'payout_delay_hours') * interval '1 hour' AS release_at
    ) rel
    WHERE r.id::text LIKE '00000000-0000-0000-0000-00000000d0%'
      AND NOT (r.status = 'CANCELLED' AND r.cancel_reason LIKE 'Payment failed%');

    -- ------------------------------------------------------------ reviews

    INSERT INTO reviews (id, reservation_id, author_id, reviewee_id, spot_id, rating, comment, created_at, updated_at)
    SELECT gen_random_uuid(), r.id, r.driver_id, s.owner_id, s.id, x.rating, x.comment,
           r.check_out_at + interval '3 hours', r.check_out_at + interval '3 hours'
    FROM (VALUES
        ('00000000-0000-0000-0000-00000000d001'::uuid, 5, 'Easy to find, the gate code worked at once.'),
        ('00000000-0000-0000-0000-00000000d002'::uuid, 4, 'Clean and covered. The entrance is a bit tight.'),
        ('00000000-0000-0000-0000-00000000d003'::uuid, 5, 'The charger worked and the space is wide.')
    ) AS x (reservation_id, rating, comment)
    JOIN reservations r ON r.id = x.reservation_id
    JOIN parking_spots s ON s.id = r.spot_id;

    UPDATE parking_spots p
    SET avg_rating = agg.average, review_count = agg.cnt
    FROM (SELECT spot_id, round(avg(rating)::numeric, 2) AS average, count(*)::int AS cnt
          FROM reviews WHERE status = 'VISIBLE' GROUP BY spot_id) agg
    WHERE agg.spot_id = p.id;

    -- ------------------------------------------------------------ notifications (the texts the services create)

    INSERT INTO notifications (id, user_id, type, title, body, payload, channel, read_at, sent_at, created_at, updated_at)
    SELECT gen_random_uuid(), n.user_id, n.type, n.title,
           replace(replace(replace(n.body, '{spot}', '"' || s.name || '"'), '{code}', r.code), '{pin}', COALESCE(r.pin_code, '')),
           jsonb_build_object('reservationId', r.id, 'code', r.code, 'spotId', s.id),
           'IN_APP', CASE WHEN n.is_read THEN r.created_at + interval '2 hours' END, r.created_at + interval '1 minute',
           r.created_at + interval '1 minute', r.created_at + interval '1 minute'
    FROM (VALUES
        ('00000000-0000-0000-0000-00000000d006'::uuid, '00000000-0000-0000-0000-000000000002'::uuid, 'RESERVATION_CONFIRMED', 'Rezerwacja potwierdzona',
         'Twój kod PIN do bramy: {pin}, miejsce {spot} ({code}).', false),
        ('00000000-0000-0000-0000-00000000d006', '00000000-0000-0000-0000-000000000001', 'NEW_RESERVATION', 'Nowa rezerwacja',
         'Miejsce {spot} zostało zarezerwowane ({code}).', false),
        ('00000000-0000-0000-0000-00000000d007', '00000000-0000-0000-0000-000000000001', 'NEW_RESERVATION_REQUEST', 'Nowa prośba o rezerwację',
         'Prośba o rezerwację miejsca {spot} ({code}) czeka na Twoją decyzję.', false),
        ('00000000-0000-0000-0000-00000000d005', '00000000-0000-0000-0000-000000000003', 'DRIVER_CHECKED_IN', 'Kierowca przyjechał',
         'Kierowca rozpoczął postój na miejscu {spot} ({code}).', false),
        ('00000000-0000-0000-0000-00000000d008', '00000000-0000-0000-0000-000000000003', 'RESERVATION_CANCELLED', 'Rezerwacja anulowana',
         'Rezerwacja {code} miejsca {spot} została anulowana.', true),
        ('00000000-0000-0000-0000-00000000d009', '00000000-0000-0000-0000-000000000003', 'RESERVATION_REJECTED', 'Rezerwacja odrzucona',
         'Właściciel odrzucił rezerwację {code}. Płatność zostanie zwrócona w całości.', true),
        ('00000000-0000-0000-0000-00000000d010', '00000000-0000-0000-0000-000000000002', 'RESERVATION_EXPIRED', 'Rezerwacja wygasła',
         'Właściciel nie potwierdził rezerwacji {code} na czas. Płatność została zwrócona w całości.', true)
    ) AS n (reservation_id, user_id, type, title, body, is_read)
    JOIN reservations r ON r.id = n.reservation_id
    JOIN parking_spots s ON s.id = r.spot_id;
END
$seed$;

DROP FUNCTION IF EXISTS pg_temp.local_ts(integer, numeric);
