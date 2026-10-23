-- PARI-38: a request the owner does not answer in time becomes EXPIRED (full refund).
-- EXPIRED does not hold the slot, so the WHERE clause of reservations_no_overlap stays as it is.
ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'PENDING_APPROVAL', 'CONFIRMED',
                      'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'));

-- Values below are the ones written in the Jira tickets (not placeholders).
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('approval_timeout_minutes', '60', 'Minutes the owner has to accept or reject a request before it expires (PARI-38)'),
    ('overstay_grace_minutes',   '15', 'Minutes after the reserved end before an overstay penalty applies (PARI-37, F19)');
