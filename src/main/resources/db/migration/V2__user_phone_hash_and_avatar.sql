-- PARI-17: a phone number can belong to one account. The number itself is encrypted (phone_enc), so equality is checked
-- through phone_hash, an HMAC-SHA256 of the normalised number keyed with the data encryption key.
-- PARI-22: profile picture URL.

ALTER TABLE users ADD COLUMN phone_hash varchar(64);
ALTER TABLE users ADD COLUMN avatar_url varchar(500);

CREATE UNIQUE INDEX users_phone_hash_uq ON users (phone_hash) WHERE phone_hash IS NOT NULL;
