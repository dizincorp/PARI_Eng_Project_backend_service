-- PARI-85 / PARI-66: the owner-configured gate PIN lives in its own column on the parking space, and every reservation
-- keeps a snapshot of the gate PIN and the access instructions that were valid when it was confirmed.
--
-- owner_gate_pin and owner_gate_pin_snapshot hold the output of EncryptedStringConverter (base64 of iv || ciphertext),
-- never the plain PIN. Existing rows get NULL: legacy reservations stay without a snapshot on purpose, the spot's
-- current values are not copied into history.

ALTER TABLE parking_spots
    ADD COLUMN owner_gate_pin text;

ALTER TABLE reservations
    ADD COLUMN owner_gate_pin_snapshot      text,
    ADD COLUMN access_instructions_snapshot text;
