-- Before every test

TRUNCATE TABLE outbox_events;
TRUNCATE TABLE transactions;
TRUNCATE TABLE users CASCADE;
TRUNCATE TABLE accounts CASCADE ;

-- 1. for transfer-stress test

-- create 200 users
INSERT INTO users (id, email)
SELECT
    ('30000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    'stress' || gs || '@globalpay.dev'
FROM generate_series(1, 200) gs
    ON CONFLICT (id) DO NOTHING;

-- create 200 accounts for the users
INSERT INTO accounts (id, user_id, balance, currency)
SELECT
    ('40000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    ('30000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    1000000.0000,
    'USD'
FROM generate_series(1, 200) gs
    ON CONFLICT (id) DO NOTHING;

-- check that every account has a corresponding user
SELECT
    ('30000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid AS missing_user_id
FROM generate_series(1, 200) gs
         LEFT JOIN users u
                   ON u.id = ('30000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid
WHERE u.id IS NULL;

-- 2. for transfer-hot-contention test

-- create 2 users
INSERT INTO users (id, email)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'hot-from@globalpay.dev'),
    ('22222222-2222-2222-2222-222222222222', 'hot-to@globalpay.dev')
    ON CONFLICT (id) DO NOTHING;

-- create 2 accounts for the users
INSERT INTO accounts (id, user_id, balance, currency, version)
VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 1000000.0000, 'USD', 0),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', 0.0000,       'USD', 0)
    ON CONFLICT (id) DO NOTHING;

-- check that every account has a corresponding user
SELECT id, user_id, balance, currency, version
FROM accounts
WHERE id IN (
             'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
             'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
    );

-- 3.for transfer-idempotency-collision tets

-- create 6 users
INSERT INTO users (id, email)
SELECT
    ('20000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    'idem' || gs || '@globalpay.dev'
FROM generate_series(1, 6) gs
ON CONFLICT (id) DO NOTHING;

-- create 6 accounts for the users
INSERT INTO accounts (id, user_id, balance, currency)
SELECT
    ('40000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    ('20000000-0000-0000-0000-' || lpad(gs::text, 12, '0'))::uuid,
    999999999.0000,
    'USD'
FROM generate_series(1, 6) gs
ON CONFLICT (id) DO NOTHING;

