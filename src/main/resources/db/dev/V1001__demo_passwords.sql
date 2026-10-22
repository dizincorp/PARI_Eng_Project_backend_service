-- DEMO DATA, development only (see V1000). Gives the demo users a real BCrypt hash so they can log in:
-- anna.owner@pari.test, jan.driver@pari.test, ola.both@pari.test  /  password: Demo1234!
UPDATE users
SET password_hash = '$2a$10$UZrWcnpsGnnYqXMeABhUaezi6sZ2YTeZdrEoAPpNVi4F5iFzcbIj.'
WHERE email IN ('anna.owner@pari.test', 'jan.driver@pari.test', 'ola.both@pari.test');
