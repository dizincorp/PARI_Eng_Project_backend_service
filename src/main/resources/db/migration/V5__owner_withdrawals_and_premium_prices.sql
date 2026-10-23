-- PARI-36: owner withdrawals. A withdrawal pays out part of the balance of PAID payouts to the owner's bank account.
-- iban holds the AES-GCM encrypted value (EncryptedStringConverter), hence the generous length.
CREATE TABLE withdrawals (
    id              uuid         PRIMARY KEY,
    owner_id        uuid         NOT NULL REFERENCES users (id),
    amount_minor    bigint       NOT NULL CHECK (amount_minor > 0),
    currency        varchar(3)   NOT NULL DEFAULT 'PLN',
    iban            varchar(500) NOT NULL,
    status          varchar(10)  NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    provider_ref    varchar(255),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0
);
CREATE INDEX withdrawals_owner_idx ON withdrawals (owner_id, status);

-- PARI-23: Premium plan prices. The Jira ticket and the design document define no prices.
-- PLACEHOLDER business values: replace them with the values the team agrees on (changeable at runtime).
INSERT INTO platform_settings (setting_key, setting_value, description) VALUES
    ('premium_price_monthly_minor', '2900',  'Price of the MONTHLY Premium plan in grosze. PLACEHOLDER, not agreed'),
    ('premium_price_yearly_minor',  '29000', 'Price of the YEARLY Premium plan in grosze. PLACEHOLDER, not agreed');
